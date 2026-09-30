package com.fixy.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Core Fase 1 (partir LeadAgentService, ver CORE_FASE1_CONTRATO.md): único
 * dueño de los tres WebClient de LLM (OpenAI Responses, Ollama, Cloudflare
 * Workers AI) y de toda la config {@code @Value} de proveedor. Antes esto
 * vivía duplicado en {@link LeadAgentService} (turno conversacional) y en
 * {@link AgentService} (clasificador de intake) — cada uno con sus propios
 * WebClients apuntando a las mismas URLs. Refactor puro: los métodos
 * privados de abajo son copia textual de los que tenía cada servicio, sin
 * cambiar timeouts, reintentos, logging ni parseo de ningún proveedor.
 * Excepción posterior (2026-09-30): el parseo de OpenAI se corrigió en
 * {@link #parseResponsesBody} porque el original nunca encontraba el texto
 * con modelos razonadores (ver javadoc ahí).
 *
 * <p>API de dos niveles a propósito:
 * <ul>
 *   <li>{@link #completeText} / {@link #completeJson}: despachan según el
 *   proveedor CONFIGURADO ({@code fixy.agent.provider}) — reemplazan el
 *   switch que tenía {@code LeadAgentService.callLlm} /
 *   {@code respondAndExtractTurn}.</li>
 *   <li>{@link #completeTextOpenAi} / {@link #completeJsonWorkersAi}:
 *   proveedor EXPLÍCITO, sin mirar la config — reemplazan las llamadas de
 *   {@code AgentService.classifyWithOpenAi}/{@code classifyWithWorkersAi},
 *   que siempre llamaron a un proveedor puntual (decidido por su propia
 *   cadena de fallback en {@code classify()}) sin importar qué proveedor
 *   esté configurado para el turno conversacional.</li>
 * </ul>
 */
@Service
public class LlmGateway {

  private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

  private final ObjectMapper objectMapper;
  private final WebClient openAiClient;
  private final WebClient ollamaClient;
  private final WebClient cloudflareClient;
  private final String provider;
  private final String openAiApiKey;
  private final String openAiModel;
  private final String ollamaModel;
  private final String cloudflareAccountId;
  private final String cloudflareApiToken;
  private final String cloudflareModel;
  private final boolean enabled;

  public LlmGateway(
      ObjectMapper objectMapper,
      @Value("${fixy.openai.api-key:}") String openAiApiKey,
      @Value("${fixy.openai.model:gpt-5-mini}") String openAiModel,
      @Value("${fixy.agent.enabled:true}") boolean enabled,
      @Value("${fixy.agent.provider:openai}") String provider,
      @Value("${fixy.ollama.base-url:http://127.0.0.1:11434}") String ollamaBaseUrl,
      @Value("${fixy.ollama.model:qwen2.5:3b}") String ollamaModel,
      @Value("${fixy.cloudflare.account-id:}") String cloudflareAccountId,
      @Value("${fixy.cloudflare.api-token:}") String cloudflareApiToken,
      @Value("${fixy.cloudflare.model:@cf/meta/llama-3.3-70b-instruct-fp8-fast}") String cloudflareModel
  ) {
    this.objectMapper = objectMapper;
    this.openAiApiKey = openAiApiKey;
    this.openAiModel = openAiModel;
    this.ollamaModel = ollamaModel;
    this.cloudflareAccountId = cloudflareAccountId;
    this.cloudflareApiToken = cloudflareApiToken;
    this.cloudflareModel = cloudflareModel;
    this.enabled = enabled;
    this.provider = provider == null ? "openai" : provider.toLowerCase().trim();
    log.info("LlmGateway initialized: provider={} enabled={} cloudflareModel={} ollamaModel={}",
        this.provider, this.enabled, cloudflareModel, ollamaModel);
    this.openAiClient = WebClient.builder()
        .baseUrl("https://api.openai.com/v1")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.ollamaClient = WebClient.builder()
        .baseUrl(ollamaBaseUrl)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.cloudflareClient = WebClient.builder()
        .baseUrl("https://api.cloudflare.com/client/v4")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
        .build();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String provider() {
    return provider;
  }

  /** true si hay API key de OpenAI configurada. */
  public boolean hasOpenAiKey() {
    return openAiApiKey != null && !openAiApiKey.isBlank();
  }

  /** true si hay credenciales de Cloudflare Workers AI configuradas. */
  public boolean hasCloudflareCredentials() {
    return cloudflareAccountId != null && !cloudflareAccountId.isBlank()
        && cloudflareApiToken != null && !cloudflareApiToken.isBlank();
  }

  /**
   * Genera texto libre según el proveedor CONFIGURADO. Reemplaza
   * {@code LeadAgentService.callLlm}: ollama/openai concatenan
   * {@code systemPrompt + "\n\n" + userContent} en un único prompt (igual
   * que antes); workersai manda systemPrompt/userContent como mensajes
   * separados. Si systemPrompt es null/blank no se antepone separador —
   * caso usado por {@link #completeTextOpenAi} vía el mismo camino interno.
   */
  public String completeText(String systemPrompt, String userContent) {
    String legacyPrompt = concat(systemPrompt, userContent);
    return switch (provider) {
      case "ollama" -> callOllama(legacyPrompt);
      case "workersai" -> callWorkersAi(systemPrompt, userContent);
      default -> callOpenAi(legacyPrompt);
    };
  }

  /**
   * Extrae JSON estructurado según el proveedor CONFIGURADO. Reemplaza el
   * despacho de {@code LeadAgentService.respondAndExtractTurn}: workersai usa
   * response_format json_schema (ver {@link #completeJsonWorkersAi} para el
   * detalle del request); ollama/openai reusan el mismo camino de texto de
   * siempre (el JSON sale porque el prompt lo pide, no por un modo especial
   * del proveedor — así funcionaba antes de este refactor).
   */
  public String completeJson(String systemPrompt, String userContent, Map<String, Object> jsonSchema) {
    if ("workersai".equals(provider)) {
      return callWorkersAiJson(systemPrompt, userContent, jsonSchema, 350, 0.3, 15);
    }
    String legacyPrompt = concat(systemPrompt, userContent);
    return "ollama".equals(provider) ? callOllama(legacyPrompt) : callOpenAi(legacyPrompt);
  }

  /**
   * Llama a OpenAI puntualmente, sin mirar el proveedor configurado.
   * Reemplaza el callOpenAi que {@code AgentService.classifyWithOpenAi}
   * armaba a mano — el clasificador de intake siempre usó OpenAI cuando
   * decidía intentarlo (ver su propia cadena en {@code classify()}),
   * independientemente de {@code fixy.agent.provider}.
   */
  public String completeTextOpenAi(String prompt) {
    return callOpenAi(prompt);
  }

  /**
   * Llama a Cloudflare Workers AI puntualmente con json_schema, sin mirar el
   * proveedor configurado. Reemplaza el callWorkersAiJson que
   * {@code AgentService.classifyWithWorkersAi} armaba a mano: un solo mensaje
   * "user" (sin system), max_tokens 400, timeout 30s — parámetros DISTINTOS
   * a los del turno conversacional ({@link #completeJson}, max_tokens 350 /
   * timeout 15s / system+user), preservados tal cual para no cambiar el
   * request real que recibe Cloudflare.
   */
  public String completeJsonWorkersAi(String userContent, Map<String, Object> jsonSchema) {
    return callWorkersAiJson(null, userContent, jsonSchema, 400, 0.3, 30);
  }

  private static String concat(String systemPrompt, String userContent) {
    return (systemPrompt == null || systemPrompt.isBlank())
        ? userContent
        : systemPrompt + "\n\n" + userContent;
  }

  private String callOpenAi(String prompt) {
    if (openAiApiKey == null || openAiApiKey.isBlank()) {
      return null;
    }
    try {
      Map<String, Object> payload = AgentService.buildResponsesPayload(openAiModel, prompt);
      String raw = openAiClient.post()
          .uri("/responses")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          // 40s + retry: paridad con el path de Cloudflare; el timeout de
          // 20s cortaba la primera llamada post-boot (2026-07-27).
          .timeout(Duration.ofSeconds(40))
          .retry(1)
          .block();
      if (raw == null || raw.isBlank()) {
        log.warn("openai: respuesta vacía de /responses (modelo {})", openAiModel);
        return null;
      }
      return parseResponsesBody(raw);
    } catch (Exception ex) {
      log.warn("openai call failed: {}", ex.getMessage());
      return null;
    }
  }

  /**
   * Extrae el texto de una respuesta cruda de la Responses API de OpenAI.
   *
   * <p>Bug de prod 2026-09-02..30 (todos los turnos con gpt-5-mini caían a
   * "fallback heurístico" sin WARN): el parseo anterior solo miraba
   * {@code output_text} en la raíz (atajo que existe en los SDKs, NO en el
   * JSON HTTP) y {@code output[0].content[].text}. Con modelos razonadores
   * {@code output[0]} es un item {@code type=reasoning} sin {@code content}
   * y el {@code message} viene en {@code output[1]} — verificado con una
   * llamada real desde prod el 2026-09-30 — así que devolvía null en
   * silencio. Ahora recorre todo {@code output[]}, toma el primer item
   * {@code type=message} y dentro el primer {@code content[]} con
   * {@code type=output_text}; el atajo {@code output_text} raíz se mantiene
   * por si algún proxy/SDK lo agrega. Si no hay texto, loguea WARN con los
   * tipos recibidos y el inicio del cuerpo: nunca más un fallo mudo.
   */
  String parseResponsesBody(String raw) throws JsonProcessingException {
    JsonNode root = objectMapper.readTree(raw);
    JsonNode outputText = root.path("output_text");
    if (outputText.isTextual() && !outputText.asText().isBlank()) {
      return outputText.asText().trim();
    }
    List<String> itemTypes = new ArrayList<>();
    JsonNode output = root.path("output");
    if (output.isArray()) {
      for (JsonNode item : output) {
        String type = item.path("type").asText("");
        itemTypes.add(type);
        if (!"message".equals(type)) {
          continue;
        }
        JsonNode content = item.path("content");
        if (!content.isArray()) {
          continue;
        }
        for (JsonNode part : content) {
          String partType = part.path("type").asText("");
          if (!partType.isEmpty() && !"output_text".equals(partType)) {
            if ("refusal".equals(partType)) {
              itemTypes.add("message.refusal");
            }
            continue;
          }
          JsonNode text = part.path("text");
          if (text.isTextual() && !text.asText().isBlank()) {
            return text.asText().trim();
          }
        }
      }
    }
    String head = raw.length() > 300 ? raw.substring(0, 300) : raw;
    log.warn("openai: sin texto utilizable en /responses (modelo {}, status={}, error={}, "
            + "incomplete={}, output types={}): {}",
        openAiModel,
        root.path("status").asText("?"),
        root.path("error").isNull() || root.path("error").isMissingNode()
            ? "-" : root.path("error").toString(),
        root.path("incomplete_details").isNull() || root.path("incomplete_details").isMissingNode()
            ? "-" : root.path("incomplete_details").toString(),
        itemTypes,
        head.replace('\n', ' '));
    return null;
  }

  private String callWorkersAi(String systemContent, String userContent) {
    if (cloudflareAccountId == null || cloudflareAccountId.isBlank()
        || cloudflareApiToken == null || cloudflareApiToken.isBlank()) {
      log.warn("workersai: missing CF_ACCOUNT_ID or CF_API_TOKEN");
      return null;
    }
    try {
      Map<String, Object> payload = Map.of(
          "messages", List.of(
              Map.of("role", "system", "content", systemContent),
              Map.of("role", "user", "content", userContent)
          ),
          "max_tokens", 200,
          "temperature", 0.4
      );
      String uri = "/accounts/" + cloudflareAccountId + "/ai/run/" + cloudflareModel;
      String raw = cloudflareClient.post()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + cloudflareApiToken)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(15))
          .retry(1)
          .block();
      if (raw == null || raw.isBlank()) {
        return null;
      }
      JsonNode root = objectMapper.readTree(raw);
      if (!root.path("success").asBoolean(false)) {
        log.warn("workersai non-success: {}", raw.length() > 300 ? raw.substring(0, 300) : raw);
        return null;
      }
      JsonNode response = root.path("result").path("response");
      if (response.isTextual() && !response.asText().isBlank()) {
        return response.asText().trim();
      }
      return null;
    } catch (Exception ex) {
      log.warn("workersai call failed: {}", ex.getMessage());
      return null;
    }
  }

  private String callOllama(String prompt) {
    try {
      Map<String, Object> payload = Map.of(
          "model", ollamaModel,
          "prompt", prompt,
          "stream", false,
          "options", Map.of("temperature", 0.3, "num_predict", 150, "top_p", 0.9)
      );
      String raw = ollamaClient.post()
          .uri("/api/generate")
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(120))
          .block();
      if (raw == null || raw.isBlank()) {
        return null;
      }
      JsonNode root = objectMapper.readTree(raw);
      JsonNode response = root.path("response");
      if (response.isTextual() && !response.asText().isBlank()) {
        return response.asText().trim();
      }
      return null;
    } catch (Exception ex) {
      log.warn("ollama call failed: {}", ex.getMessage());
      return null;
    }
  }

  /**
   * Llama Workers AI pidiendo JSON puro como response. {@code systemContent}
   * null/blank omite el mensaje "system" (caso {@code completeJsonWorkersAi},
   * que nunca lo tuvo); no-blank arma system+user (caso {@code completeJson}
   * del turno conversacional, que siempre lo tuvo).
   */
  private String callWorkersAiJson(
      String systemContent, String userContent, Map<String, Object> jsonSchema,
      int maxTokens, double temperature, int timeoutSeconds) {
    if (cloudflareAccountId == null || cloudflareAccountId.isBlank()
        || cloudflareApiToken == null || cloudflareApiToken.isBlank()) {
      return null;
    }
    try {
      List<Map<String, String>> messages = (systemContent == null || systemContent.isBlank())
          ? List.of(Map.of("role", "user", "content", userContent))
          : List.of(
              Map.of("role", "system", "content", systemContent),
              Map.of("role", "user", "content", userContent));
      Map<String, Object> payload = Map.of(
          "messages", messages,
          "max_tokens", maxTokens,
          "temperature", temperature,
          "response_format", Map.of("type", "json_schema", "json_schema", jsonSchema)
      );
      String uri = "/accounts/" + cloudflareAccountId + "/ai/run/" + cloudflareModel;
      String raw = cloudflareClient.post()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + cloudflareApiToken)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(timeoutSeconds))
          .retry(1)
          .block();
      if (raw == null || raw.isBlank()) {
        return null;
      }
      JsonNode root = objectMapper.readTree(raw);
      if (!root.path("success").asBoolean(false)) {
        log.warn("workersai-json non-success: {}", raw.length() > 300 ? raw.substring(0, 300) : raw);
        return null;
      }
      JsonNode response = root.path("result").path("response");
      if (response.isTextual()) {
        return response.asText();
      }
      // Algunos modelos devuelven el objeto JSON directo en response.
      if (response.isObject()) {
        return response.toString();
      }
      return null;
    } catch (Exception ex) {
      log.warn("workersai-json call failed: {}", ex.getMessage());
      return null;
    }
  }
}
