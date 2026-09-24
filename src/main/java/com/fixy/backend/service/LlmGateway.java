package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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
        return null;
      }
      JsonNode root = objectMapper.readTree(raw);
      JsonNode outputText = root.path("output_text");
      if (outputText.isTextual() && !outputText.asText().isBlank()) {
        return outputText.asText().trim();
      }
      JsonNode output = root.path("output");
      if (output.isArray() && output.size() > 0) {
        JsonNode first = output.get(0);
        if (first.has("content") && first.get("content").isArray()) {
          for (JsonNode item : first.get("content")) {
            if (item.has("text")) {
              return item.get("text").asText().trim();
            }
          }
        }
      }
      return null;
    } catch (Exception ex) {
      log.warn("openai call failed: {}", ex.getMessage());
      return null;
    }
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
