package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.repository.LeadRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Genera mensajes conversacionales del agente Fixy en la conversación de un lead.
 * Llama a OpenAI cuando hay credencial; si falla o no hay key, usa templates.
 */
@Service
public class LeadAgentService {

  private static final Logger log = LoggerFactory.getLogger(LeadAgentService.class);
  private static final int HISTORY_LIMIT = 10;

  private static final String SYSTEM_PROMPT = """
      IDIOMA: ESPAÑOL RIOPLATENSE DE URUGUAY. NO USES PORTUGUÉS NUNCA.
      VOSEO OBLIGATORIO: usá "vos", "tenés", "sos", "podés", "querés", "te aviso", "te ayudo".
      PROHIBIDO: "tú", "tienes", "eres", "puedes", "quieres", "Olá", "endereço", "fornecedor".

      NO MENCIONES METADATOS INTERNOS AL CLIENTE: no digas "pedido ID 67", "ID: 68", "categoría detectada", "servicio detectado".
      El cliente no quiere ver ids ni etiquetas técnicas. Hablale como persona, no como ticket.

      Sos Fixy, asistente conversacional del marketplace de servicios del hogar Fixy.
      Operás primero en Ciudad de la Costa, Canelones, Uruguay.

      Tu rol: ayudar al cliente a completar su pedido y avisarle cuándo un proveedor se hace cargo.
      Servicios que cubrimos: plomería, barométrica, jardinería, aire acondicionado.
      Zonas que cubrimos: Solymar, Lagomar, El Pinar, Shangrilá, Barra de Carrasco, Parque Miramar,
      San José de Carrasco, Lomas de Solymar, Colinas de Solymar, Aeroparque, Ciudad de la Costa.

      Reglas duras:
      - Máximo 3 oraciones por mensaje. Sin listas, bullets ni viñetas.
      - Si falta info clave (foto, dirección), pedila natural en UN mensaje, sin enumerar.
      - Si no hay proveedores en la zona+categoría: decí que avisás cuando aparezca uno, sin alarmar.
      - Si la zona está fuera de cobertura: decílo con honestidad, guardás el pedido igual.
      - Nunca prometas tiempos exactos; usá "en minutos", "hoy", "esta semana" según la urgencia.
      - Nunca pidas datos de pago; Fixy no le cobra al cliente.
      - No te disculpes por cosas que no rompiste. Directa y útil.
      - No agregues firma ni "Saludos, Fixy".

      Ejemplos de buen tono (usá la categoría que corresponda al pedido del cliente, no copies "plomero" literal):
      - "Recibí tu pedido. Para que el proveedor te pase precio firme me falta una foto y la dirección exacta — ¿me las pasás?"
      - "Lo paso a un proveedor de la zona. Te aviso por acá apenas alguien tome el pedido."
      - "Hoy no tenemos proveedores disponibles en esa zona, pero te aviso apenas haya uno libre."
      """;

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
  private final LeadMessageService leadMessageService;
  private final LeadRepository leadRepository;
  private final ProviderCatalogService providerCatalogService;

  public LeadAgentService(
      ObjectMapper objectMapper,
      LeadMessageService leadMessageService,
      LeadRepository leadRepository,
      ProviderCatalogService providerCatalogService,
      @Value("${fixy.openai.api-key:}") String openAiApiKey,
      @Value("${fixy.openai.model:gpt-4.1-mini}") String openAiModel,
      @Value("${fixy.agent.enabled:true}") boolean enabled,
      @Value("${fixy.agent.provider:openai}") String provider,
      @Value("${fixy.ollama.base-url:http://127.0.0.1:11434}") String ollamaBaseUrl,
      @Value("${fixy.ollama.model:qwen2.5:3b}") String ollamaModel,
      @Value("${fixy.cloudflare.account-id:}") String cloudflareAccountId,
      @Value("${fixy.cloudflare.api-token:}") String cloudflareApiToken,
      @Value("${fixy.cloudflare.model:@cf/meta/llama-3.1-8b-instruct}") String cloudflareModel
  ) {
    this.objectMapper = objectMapper;
    this.leadMessageService = leadMessageService;
    this.leadRepository = leadRepository;
    this.providerCatalogService = providerCatalogService;
    this.openAiApiKey = openAiApiKey;
    this.openAiModel = openAiModel;
    this.ollamaModel = ollamaModel;
    this.cloudflareAccountId = cloudflareAccountId;
    this.cloudflareApiToken = cloudflareApiToken;
    this.cloudflareModel = cloudflareModel;
    this.enabled = enabled;
    this.provider = provider == null ? "openai" : provider.toLowerCase().trim();
    log.info("LeadAgentService initialized: provider={} enabled={} cloudflareModel={} ollamaModel={}",
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

  /** Mensaje inicial del agente cuando se crea un lead. Async — el POST no espera al LLM. */
  @Async
  public void greet(Lead lead) {
    if (!enabled) return;
    try {
      String context = buildContext(lead);
      String instruction;
      boolean isChatFirst = lead.getDetectedCategory() == null
          && (lead.getProblem() == null || "(pendiente)".equals(lead.getProblem()));
      if (isChatFirst) {
        instruction = """
            Es la primera vez que hablás con este cliente y todavía no sabés qué necesita.
            Presentate brevemente y preguntale qué le pasa o qué necesita arreglar.
            No menciones servicios específicos todavía. Máximo 2 oraciones.
            """;
      } else {
        instruction = """
            Es la primera vez que hablás con este cliente. Saludá brevemente,
            confirmá el pedido en tus palabras y pediles lo que falte para
            conseguir un proveedor. Si la zona o la categoría están fuera de
            alcance, decílo con honestidad. Máximo 4 oraciones.
            """;
      }
      String reply = callLlm(context, instruction);
      if (reply == null || reply.isBlank()) {
        reply = isChatFirst ? fallbackChatFirstGreeting() : fallbackGreeting(lead);
      }
      leadMessageService.postFromAgent(lead.getId(), reply);
    } catch (Exception ex) {
      log.warn("greet failed for lead {}: {}", lead.getId(), ex.getMessage());
      safePost(lead.getId(), fallbackGreeting(lead));
    }
  }

  private String fallbackChatFirstGreeting() {
    return "Hola, soy Fixy. Contame qué necesitás arreglar o coordinar y te ayudo a conseguir un proveedor.";
  }

  /**
   * Genera la respuesta del agente al último mensaje del cliente Y extrae
   * datos estructurados (categoría, zona, urgencia, teléfono, etc.) para
   * actualizar el Lead progresivamente. Async — el frontend recoge la respuesta
   * por polling.
   */
  @Async
  public void respondToCustomerAsync(Long leadId) {
    if (!enabled) return;
    Lead lead = leadRepository.findById(leadId).orElse(null);
    if (lead == null) {
      log.warn("respondToCustomer: lead {} not found", leadId);
      return;
    }
    try {
      String context = buildContext(lead);
      String history = renderHistory(leadMessageService.recentForAgent(leadId, HISTORY_LIMIT));
      AgentTurnResult result = respondAndExtractTurn(lead, context, history);
      String reply = (result == null || result.reply() == null || result.reply().isBlank())
          ? fallbackResponse(lead)
          : result.reply();
      leadMessageService.postFromAgent(leadId, reply);
      if (result != null && result.extracted() != null && !result.extracted().isEmpty()) {
        applyExtractedFields(leadId, result.extracted());
      }
    } catch (Exception ex) {
      log.warn("respondToCustomer failed for lead {}: {}", leadId, ex.getMessage());
      safePost(leadId, fallbackResponse(lead));
    }
  }

  private record AgentTurnResult(String reply, Map<String, String> extracted) {}

  private AgentTurnResult respondAndExtractTurn(Lead lead, String context, String history) {
    String userContent = context + "\n\nConversación reciente:\n" + history + """


        TAREA:
        1) Respondé al ÚLTIMO mensaje del cliente en español rioplatense (voseo), breve y útil.
           Si el cliente recién pasó info (foto, dirección, detalles), agradecela y avanzá.
           No repitas lo que ya dijiste. Si todavía no sabés qué necesita, preguntá.
        2) Extraé datos estructurados del cliente que aparezcan en la conversación.

        FORMATO DE SALIDA: SOLO un JSON válido, sin texto antes ni después, con esta estructura:
        {
          "reply": "tu respuesta conversacional al cliente",
          "extracted": {
            "category": "plomeria|barometrica|jardineria|aires_acondicionados|otro|null",
            "zone": "Solymar|Lagomar|El Pinar|Shangrilá|Barra de Carrasco|Parque Miramar|San José de Carrasco|Lomas de Solymar|Colinas de Solymar|Aeroparque|Ciudad de la Costa|otro|null",
            "urgency": "alta|media|baja|null",
            "phone": "099XXXXXX o null",
            "name": "nombre o null",
            "address": "dirección exacta o null",
            "details": "detalles relevantes o null"
          }
        }

        Reglas para extracted:
        - Usá null cuando el dato no aparezca en la conversación (no inventes).
        - Sólo extraé valores que el cliente dijo explícitamente o son obvios del contexto.
        - phone debe tener formato uruguayo: 8-9 dígitos empezando con 09 ó 9.
        """;
    String raw;
    if ("workersai".equals(provider)) {
      raw = callWorkersAiJson(SYSTEM_PROMPT, userContent);
    } else if ("ollama".equals(provider)) {
      raw = callOllama(SYSTEM_PROMPT + "\n\n" + userContent);
    } else {
      raw = callOpenAi(SYSTEM_PROMPT + "\n\n" + userContent);
    }
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return parseTurnJson(raw);
  }

  /** Llama Workers AI pidiendo JSON puro como response. */
  private String callWorkersAiJson(String systemContent, String userContent) {
    if (cloudflareAccountId == null || cloudflareAccountId.isBlank()
        || cloudflareApiToken == null || cloudflareApiToken.isBlank()) {
      return null;
    }
    try {
      List<String> categoryEnum = List.of("plomeria", "barometrica", "jardineria", "aires_acondicionados", "otro");
      List<String> zoneEnum = List.of("Solymar", "Lagomar", "El Pinar", "Shangrilá", "Barra de Carrasco",
          "Parque Miramar", "San José de Carrasco", "Lomas de Solymar", "Colinas de Solymar",
          "Aeroparque", "Ciudad de la Costa", "otro");
      List<String> urgencyEnum = List.of("alta", "media", "baja");
      Map<String, Object> turnSchema = Map.of(
          "type", "object",
          "properties", Map.of(
              "reply", Map.of("type", "string"),
              "extracted", Map.of(
                  "type", "object",
                  "properties", Map.of(
                      "category", Map.of("type", "string", "enum", categoryEnum),
                      "zone", Map.of("type", "string", "enum", zoneEnum),
                      "urgency", Map.of("type", "string", "enum", urgencyEnum),
                      "phone", Map.of("type", "string"),
                      "name", Map.of("type", "string"),
                      "address", Map.of("type", "string"),
                      "details", Map.of("type", "string")
                  )
              )
          ),
          "required", List.of("reply", "extracted")
      );
      Map<String, Object> payload = Map.of(
          "messages", List.of(
              Map.of("role", "system", "content", systemContent),
              Map.of("role", "user", "content", userContent)
          ),
          "max_tokens", 350,
          "temperature", 0.3,
          "response_format", Map.of("type", "json_schema", "json_schema", turnSchema)
      );
      String uri = "/accounts/" + cloudflareAccountId + "/ai/run/" + cloudflareModel;
      String raw = cloudflareClient.post()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + cloudflareApiToken)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(30))
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

  private AgentTurnResult parseTurnJson(String raw) {
    try {
      String trimmed = raw.trim();
      // Algunos modelos meten ```json ``` o texto extra. Buscar el primer { y último }.
      int first = trimmed.indexOf('{');
      int last = trimmed.lastIndexOf('}');
      if (first < 0 || last <= first) {
        log.warn("turn-json: no JSON object found in response (first={}, last={})", first, last);
        return new AgentTurnResult(raw.trim(), java.util.Map.of());
      }
      String jsonOnly = trimmed.substring(first, last + 1);
      JsonNode root = objectMapper.readTree(jsonOnly);
      String reply = root.path("reply").asText("").trim();
      JsonNode ext = root.path("extracted");
      java.util.Map<String, String> extracted = new java.util.HashMap<>();
      if (ext.isObject()) {
        for (String key : List.of("category", "zone", "urgency", "phone", "name", "address", "details")) {
          JsonNode v = ext.path(key);
          if (v.isTextual()) {
            String s = v.asText().trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
              extracted.put(key, s);
            }
          }
        }
      }
      if (reply.isEmpty()) {
        return new AgentTurnResult(null, extracted);
      }
      return new AgentTurnResult(reply, extracted);
    } catch (Exception ex) {
      log.warn("turn-json parse failed: {}", ex.getMessage());
      // Fallback: tratar todo el raw como reply text (sin extracción).
      return new AgentTurnResult(raw.trim(), java.util.Map.of());
    }
  }

  /**
   * Aplica campos extraídos al Lead. Solo escribe sobre campos vacíos
   * para no sobrescribir info confirmada en turnos anteriores.
   */
  private void applyExtractedFields(Long leadId, Map<String, String> extracted) {
    try {
      Lead lead = leadRepository.findById(leadId).orElse(null);
      if (lead == null) return;
      boolean changed = false;
      String cat = extracted.get("category");
      if (cat != null && !cat.equalsIgnoreCase("otro") && (lead.getDetectedCategory() == null || lead.getDetectedCategory().isBlank())) {
        lead.setDetectedCategory(cat.toLowerCase().trim());
        changed = true;
      }
      String zone = extracted.get("zone");
      if (zone != null && !zone.equalsIgnoreCase("otro") && (lead.getLocation() == null || lead.getLocation().isBlank())) {
        lead.setLocation(zone.trim());
        changed = true;
      }
      String urgency = extracted.get("urgency");
      if (urgency != null && (lead.getUrgency() == null || lead.getUrgency().isBlank())) {
        lead.setUrgency(urgency.toLowerCase().trim());
        changed = true;
      }
      String phone = extracted.get("phone");
      if (phone != null && (lead.getPhone() == null || lead.getPhone().isBlank())) {
        lead.setPhone(phone.trim());
        changed = true;
      }
      String name = extracted.get("name");
      if (name != null && (lead.getName() == null || lead.getName().isBlank())) {
        lead.setName(name.trim());
        changed = true;
      }
      String address = extracted.get("address");
      String details = extracted.get("details");
      String currentNotes = lead.getNotes() == null ? "" : lead.getNotes();
      if (address != null && !currentNotes.contains(address)) {
        currentNotes = currentNotes.isBlank() ? ("Dirección: " + address) : (currentNotes + "\nDirección: " + address);
        lead.setNotes(currentNotes);
        changed = true;
      }
      if (details != null && !currentNotes.contains(details)) {
        currentNotes = currentNotes.isBlank() ? details : (currentNotes + "\n" + details);
        lead.setNotes(currentNotes);
        changed = true;
      }
      if (lead.getProblem() == null || "(pendiente)".equals(lead.getProblem())) {
        String composed = composeProblemFromExtracted(extracted);
        if (composed != null) {
          lead.setProblem(composed);
          changed = true;
        }
      }
      if (changed) {
        leadRepository.save(lead);
      }
    } catch (Exception ex) {
      log.warn("applyExtractedFields failed for lead {}: {}", leadId, ex.getMessage());
    }
  }

  private String composeProblemFromExtracted(Map<String, String> extracted) {
    String cat = extracted.get("category");
    String details = extracted.get("details");
    if (details != null && !details.isBlank()) {
      return details;
    }
    if (cat != null && !cat.isBlank() && !cat.equalsIgnoreCase("otro")) {
      return "Pedido de " + humanCategory(cat);
    }
    return null;
  }

  private String callLlm(String context, String instruction) {
    String userContent = context + "\n\n" + instruction;
    String legacyPrompt = SYSTEM_PROMPT + "\n\n" + userContent;
    return switch (provider) {
      case "ollama" -> callOllama(legacyPrompt);
      case "workersai" -> callWorkersAi(SYSTEM_PROMPT, userContent);
      default -> callOpenAi(legacyPrompt);
    };
  }

  private String callOpenAi(String prompt) {
    if (openAiApiKey == null || openAiApiKey.isBlank()) {
      return null;
    }
    try {
      Map<String, Object> payload = Map.of(
          "model", openAiModel,
          "input", prompt
      );
      String raw = openAiClient.post()
          .uri("/responses")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(20))
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
          .timeout(Duration.ofSeconds(30))
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

  private String buildContext(Lead lead) {
    String category = humanCategory(safe(lead.getDetectedCategory(), "sin definir"));
    String location = safe(lead.getLocation(), "sin definir");
    String urgency = safe(lead.getUrgency(), "no especificada");
    int providerCount = countProvidersInZone(category, location);
    String missing = safe(lead.getMissingFields(), "").replace("||", ", ");
    if (missing.isBlank()) missing = "ninguno";

    String coverageHint = "";
    String action = safe(deriveNextAction(lead), "");
    if ("out_of_coverage_area".equals(action)) {
      coverageHint = "\nINSTRUCCION DURA: la zona '" + location + "' NO ESTA EN COBERTURA. Decile al cliente con honestidad que todavia no operás ahí, que guardás el pedido y le avisás cuando llegues a esa zona. NO INVENTES otra zona ni le ofrezcas un proveedor.\n";
    } else if ("out_of_scope_category".equals(action)) {
      coverageHint = "\nINSTRUCCION DURA: el servicio '" + category + "' NO ESTA en la lista MVP. Decile que todavía no cubrís ese rubro y que guardás el pedido para cuando lo sumes. NO le ofrezcas un proveedor.\n";
    } else if (providerCount == 0) {
      coverageHint = "\nINSTRUCCION: ahora mismo no hay proveedores libres en '" + location + "' para " + category + ". Avisá que vas a contactar apenas aparezca uno. No alarmes.\n";
    }

    return """
        Contexto del pedido (interno, NO compartir IDs al cliente):
        - ID interno: %d
        - Problema reportado: %s
        - Servicio: %s
        - Zona: %s
        - Urgencia: %s
        - Datos faltantes: %s
        - Proveedores disponibles en esa zona+servicio: %d
        %s
        """.formatted(
        lead.getId(),
        safe(lead.getProblem(), ""),
        category,
        location,
        urgency,
        missing,
        providerCount,
        coverageHint
    );
  }

  private static final java.util.Set<String> MVP_CATEGORIES =
      java.util.Set.of("plomeria", "barometrica", "jardineria", "aires_acondicionados");
  private static final java.util.Set<String> MVP_LOCATIONS = java.util.Set.of(
      "ciudad de la costa", "solymar", "lagomar", "el pinar", "shangrila", "shangrilá",
      "barra de carrasco", "parque miramar", "san jose de carrasco", "san josé de carrasco",
      "lomas de solymar", "colinas de solymar", "aeroparque");

  private String deriveNextAction(Lead lead) {
    String cat = lead.getDetectedCategory() == null ? "" : lead.getDetectedCategory().toLowerCase().trim();
    String loc = lead.getLocation() == null ? "" : lead.getLocation().toLowerCase().trim();
    if (!cat.isBlank() && !"otro".equals(cat) && !MVP_CATEGORIES.contains(cat)) {
      return "out_of_scope_category";
    }
    if (!loc.isBlank() && !"sin definir".equals(loc) && !MVP_LOCATIONS.contains(loc)) {
      return "out_of_coverage_area";
    }
    return "ok";
  }

  private int countProvidersInZone(String category, String location) {
    if (category == null || category.isBlank() || location == null || location.isBlank()) {
      return 0;
    }
    try {
      List<ProviderCatalogItem> matches = providerCatalogService.findMatches(category, location);
      return matches == null ? 0 : matches.size();
    } catch (Exception ex) {
      return 0;
    }
  }

  private String renderHistory(List<LeadMessage> messages) {
    if (messages == null || messages.isEmpty()) {
      return "(sin mensajes previos)";
    }
    return messages.stream()
        .map(m -> "[" + roleLabel(m.getSender()) + "] " + m.getText())
        .collect(Collectors.joining("\n"));
  }

  private String roleLabel(String sender) {
    return switch (sender == null ? "" : sender.toLowerCase()) {
      case "customer" -> "cliente";
      case "fixy" -> "fixy";
      case "provider" -> "proveedor";
      default -> sender;
    };
  }

  private String fallbackGreeting(Lead lead) {
    String category = humanCategory(safe(lead.getDetectedCategory(), "tu pedido"));
    String location = safe(lead.getLocation(), "tu zona");
    String missing = humanMissing(lead.getMissingFields());
    if (!missing.isBlank()) {
      return "Hola, soy Fixy. Recibí tu pedido de %s en %s. Para conseguirte un proveedor que pase precio firme me falta %s. ¿Me lo pasás por acá?"
          .formatted(category, location, missing);
    }
    return "Hola, soy Fixy. Ya recibí tu pedido de %s en %s. Estoy buscando un proveedor disponible — te aviso por acá apenas alguien acepte.".formatted(category, location);
  }

  private String fallbackResponse(Lead lead) {
    if (lead == null) {
      return "Gracias, lo paso al proveedor.";
    }
    String missing = humanMissing(lead.getMissingFields());
    if (!missing.isBlank()) {
      return "Gracias por el dato. Todavía me falta %s para terminar de armar el caso.".formatted(missing);
    }
    return "Gracias, lo paso al proveedor. Te aviso por acá cuando alguien tome el pedido.";
  }

  private String humanCategory(String raw) {
    return switch (raw == null ? "" : raw.toLowerCase().trim()) {
      case "plomeria" -> "plomería";
      case "barometrica" -> "barométrica";
      case "jardineria" -> "jardinería";
      case "aires_acondicionados" -> "aire acondicionado";
      case "electricidad" -> "electricidad";
      case "cerrajeria" -> "cerrajería";
      case "reparaciones" -> "reparaciones";
      default -> raw == null || raw.isBlank() ? "tu pedido" : raw;
    };
  }

  private String humanMissing(String missingFieldsRaw) {
    if (missingFieldsRaw == null || missingFieldsRaw.isBlank()) {
      return "";
    }
    String[] parts = missingFieldsRaw.split("\\|\\|");
    if (parts.length == 1) return parts[0];
    if (parts.length == 2) return parts[0] + " y " + parts[1];
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length - 1; i++) {
      if (i > 0) sb.append(", ");
      sb.append(parts[i]);
    }
    sb.append(" y ").append(parts[parts.length - 1]);
    return sb.toString();
  }

  private void safePost(Long leadId, String text) {
    try {
      leadMessageService.postFromAgent(leadId, text);
    } catch (Exception ex) {
      log.error("could not persist fallback agent message for lead {}: {}", leadId, ex.getMessage());
    }
  }

  private String safe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
