package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class AgentService {

  private static final Logger log = LoggerFactory.getLogger(AgentService.class);

  // El catálogo de zonas vive en com.fixy.backend.model.CoverageZone (fuente
  // única). Acá se consulta con fromLabel, que ya normaliza mayúsculas y tildes.

  /**
   * Valores canónicos de "area" (display) + "sin definir", usados como enum estricto en el
   * json_schema de Cloudflare Workers AI para que el modelo no devuelva texto libre.
   */
  private static final List<String> CANONICAL_AREAS = List.of(
      "Solymar", "Lagomar", "El Pinar", "Shangrilá", "Barra de Carrasco", "Parque Miramar",
      "San José de Carrasco", "Lomas de Solymar", "Colinas de Solymar", "Montes de Solymar", "Aeroparque",
      "Ciudad de la Costa", "sin definir"
  );

  /**
   * Token distintivo por zona MVP (sin acentos, minúscula) usado por
   * {@link #detectArea} para matchear el nombre completo o solo un fragmento
   * característico que el cliente escribe en lenguaje natural (ej. "vivo en
   * los montes" → Montes de Solymar). Orden = prioridad de match: las
   * entradas de zonas específicas van ANTES que "solymar" a secas, porque
   * "lomas de solymar"/"colinas de solymar"/"montes de solymar" contienen la
   * palabra "solymar" y matchearían el genérico primero si el orden fuera al
   * revés (caso real lead #132: "montes" cayó a "Ciudad de la Costa").
   * LinkedHashMap: el orden de inserción es el orden de evaluación.
   */
  private static final java.util.LinkedHashMap<String, String> ZONE_TOKENS = buildZoneTokens();

  private static java.util.LinkedHashMap<String, String> buildZoneTokens() {
    java.util.LinkedHashMap<String, String> tokens = new java.util.LinkedHashMap<>();
    tokens.put("montes de solymar", "montes de solymar");
    tokens.put("montes", "montes de solymar");
    tokens.put("lomas de solymar", "lomas de solymar");
    tokens.put("lomas", "lomas de solymar");
    tokens.put("colinas de solymar", "colinas de solymar");
    tokens.put("colinas", "colinas de solymar");
    tokens.put("shangrila", "shangrilá");
    tokens.put("shangrilá", "shangrilá");
    tokens.put("el pinar", "el pinar");
    tokens.put("pinar", "el pinar");
    tokens.put("barra de carrasco", "barra de carrasco");
    tokens.put("parque miramar", "parque miramar");
    tokens.put("miramar", "parque miramar");
    tokens.put("san jose de carrasco", "san josé de carrasco");
    tokens.put("san josé de carrasco", "san josé de carrasco");
    tokens.put("aeroparque", "aeroparque");
    tokens.put("lagomar", "lagomar");
    tokens.put("solymar", "solymar");
    return tokens;
  }

  private static final String INTAKE_PROMPT_TEMPLATE = PromptLoader.load("prompts/intake-classifier.md");

  private final ObjectMapper objectMapper;
  private final WebClient webClient;
  private final WebClient cloudflareClient;
  private final String openAiApiKey;
  private final String openAiModel;
  private final String provider;
  private final String cloudflareAccountId;
  private final String cloudflareApiToken;
  private final String cloudflareModel;

  /**
   * Constructor legacy (3 args), usado por tests existentes que no necesitan multi-proveedor
   * (siempre cayeron a heurística con apiKey=""). Mantiene compatibilidad binaria: equivale a
   * provider="openai" sin credenciales de Cloudflare.
   */
  public AgentService(ObjectMapper objectMapper, String openAiApiKey, String openAiModel) {
    this(objectMapper, openAiApiKey, openAiModel, "openai", "", "", "");
  }

  @Autowired
  public AgentService(
      ObjectMapper objectMapper,
      @Value("${fixy.openai.api-key:}") String openAiApiKey,
      @Value("${fixy.openai.model:gpt-5-mini}") String openAiModel,
      @Value("${fixy.agent.provider:openai}") String provider,
      @Value("${fixy.cloudflare.account-id:}") String cloudflareAccountId,
      @Value("${fixy.cloudflare.api-token:}") String cloudflareApiToken,
      @Value("${fixy.cloudflare.model:@cf/meta/llama-3.3-70b-instruct-fp8-fast}") String cloudflareModel
  ) {
    this.objectMapper = objectMapper;
    this.openAiApiKey = openAiApiKey;
    this.openAiModel = openAiModel;
    this.provider = provider == null ? "openai" : provider.toLowerCase(Locale.ROOT).trim();
    this.cloudflareAccountId = cloudflareAccountId;
    this.cloudflareApiToken = cloudflareApiToken;
    this.cloudflareModel = cloudflareModel;
    this.webClient = WebClient.builder()
        .baseUrl("https://api.openai.com/v1")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.cloudflareClient = WebClient.builder()
        .baseUrl("https://api.cloudflare.com/client/v4")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
        .build();
  }

  public IntakeResponse classify(IntakeRequest request) {
    IntakeResponse response = null;
    if ("workersai".equals(provider) && hasCloudflareCredentials()) {
      response = classifyWithWorkersAi(request);
      if (response == null) {
        log.warn("workersai classify call failed or returned null, degrading to heuristic");
      }
    } else if (!openAiApiKey.isBlank()) {
      response = classifyWithOpenAi(request);
    }

    if (response == null) {
      response = classifyHeuristically(request);
    }

    return applyStructuredFields(request, response);
  }

  private boolean hasCloudflareCredentials() {
    return cloudflareAccountId != null && !cloudflareAccountId.isBlank()
        && cloudflareApiToken != null && !cloudflareApiToken.isBlank();
  }

  private IntakeResponse classifyWithOpenAi(IntakeRequest request) {
    String prompt = INTAKE_PROMPT_TEMPLATE.formatted(
        safe(request.contactName()),
        safe(request.phone()),
        safe(request.channel()),
        safe(request.serviceCategory()),
        safe(request.zone()),
        safe(request.urgency()),
        safe(request.address()),
        safe(request.details()),
        request.message()
    );

    try {
      Map<String, Object> payload = buildResponsesPayload(openAiModel, prompt);

      String raw = webClient.post()
          .uri("/responses")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          // 40s + retry: la PRIMera llamada tras el boot paga arranque frío
          // (pool TLS + colas del proveedor) y con 20s se caía al heurístico
          // (visto 2026-07-27 evaluando gpt-5-mini). Mismo patrón que el
          // path de Cloudflare.
          .timeout(Duration.ofSeconds(40))
          .retry(1)
          .block();

      if (raw == null || raw.isBlank()) {
        return null;
      }

      JsonNode root = objectMapper.readTree(raw);
      JsonNode outputText = root.path("output").isArray() ? root.path("output").get(0) : null;
      String text = extractText(root, outputText);

      if (text == null || text.isBlank()) {
        return null;
      }

      JsonNode result = objectMapper.readTree(text);
      return new IntakeResponse(
          result.path("leadType").asText("cliente"),
          com.fixy.backend.model.ServiceCategory.refineCategoryId(request.message(), result.path("serviceCategory").asText("otro")),
          result.path("area").asText(detectArea(request.message())),
          result.path("urgency").asText("media"),
          result.path("summary").asText(buildSummary(request, detectService(request.message()))),
          readMissingFields(result.path("missingFields")),
          result.path("suggestedReply").asText(buildSuggestedReply(request, readMissingFields(result.path("missingFields")))),
          "openai"
      );
    } catch (Exception ex) {
      // Si esto falla en silencio, el sistema degrada al heurístico sin dejar rastro.
      // WARN visible es la única señal de que OpenAI está rechazando la llamada (ej. 400 por
      // parámetro no soportado en un modelo nuevo).
      log.warn("openai classify call failed, degrading to heuristic: {}", ex.getMessage());
      return null;
    }
  }

  /**
   * Clasifica el intake vía Cloudflare Workers AI, pidiendo JSON estructurado con
   * response_format json_schema (mismo contrato ya validado en producción por
   * LeadAgentService.callWorkersAiJson). El schema espeja las mismas 7 claves que
   * produce el prompt intake-classifier.md para OpenAI, así el resto del pipeline
   * (applyStructuredFields, etc.) no distingue de dónde vino la respuesta.
   */
  private IntakeResponse classifyWithWorkersAi(IntakeRequest request) {
    String prompt = INTAKE_PROMPT_TEMPLATE.formatted(
        safe(request.contactName()),
        safe(request.phone()),
        safe(request.channel()),
        safe(request.serviceCategory()),
        safe(request.zone()),
        safe(request.urgency()),
        safe(request.address()),
        safe(request.details()),
        request.message()
    );

    try {
      Map<String, Object> payload = Map.of(
          "messages", List.of(Map.of("role", "user", "content", prompt)),
          "max_tokens", 400,
          "temperature", 0.3,
          "response_format", Map.of("type", "json_schema", "json_schema", intakeJsonSchema())
      );
      String uri = "/accounts/" + cloudflareAccountId + "/ai/run/" + cloudflareModel;
      // 1 solo retry ante timeout/error transitorio (latencia de CF es variable). Si vuelve a
      // fallar, el catch de abajo degrada a heurística — no vale la pena reintentar más veces
      // para un intake conversacional de baja latencia.
      String raw = cloudflareClient.post()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + cloudflareApiToken)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(30))
          .retry(1)
          .block();

      JsonNode result = parseWorkersAiResult(objectMapper, raw);
      if (result == null) {
        return null;
      }

      return new IntakeResponse(
          result.path("leadType").asText("cliente"),
          com.fixy.backend.model.ServiceCategory.refineCategoryId(request.message(), result.path("serviceCategory").asText("otro")),
          normalizeAreaValue(result.path("area").asText(detectArea(request.message()))),
          result.path("urgency").asText("media"),
          result.path("summary").asText(buildSummary(request, detectService(request.message()))),
          readMissingFields(result.path("missingFields")),
          result.path("suggestedReply").asText(buildSuggestedReply(request, readMissingFields(result.path("missingFields")))),
          "workersai"
      );
    } catch (Exception ex) {
      log.warn("workersai classify call failed, degrading to heuristic: {}", ex.getMessage());
      return null;
    }
  }

  /**
   * Parsea el body crudo de la respuesta de Cloudflare Workers AI y devuelve el JsonNode con
   * las 7 claves del contrato de intake, o null si la llamada fue no-exitosa / vacía / con
   * forma inesperada. Extraído como estático (sin red) para poder testear el parseo de
   * "response" como string (el caso normal con response_format json_schema) y como objeto
   * (algunos modelos devuelven el JSON ya parseado) sin necesitar mockear WebClient.
   */
  static JsonNode parseWorkersAiResult(ObjectMapper mapper, String raw) throws Exception {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    JsonNode root = mapper.readTree(raw);
    if (!root.path("success").asBoolean(false)) {
      return null;
    }
    JsonNode response = root.path("result").path("response");
    if (response.isTextual()) {
      String text = response.asText();
      if (text.isBlank()) {
        return null;
      }
      return mapper.readTree(text);
    }
    if (response.isObject()) {
      return response;
    }
    return null;
  }

  /**
   * json_schema del contrato de salida del clasificador de intake, exigido por Cloudflare
   * Workers AI vía response_format. Mismas 7 claves que devuelve el prompt intake-classifier.md.
   */
  static Map<String, Object> intakeJsonSchema() {
    List<String> leadTypeEnum = List.of("cliente", "proveedor");
    // Fuente única: com.fixy.backend.model.ServiceCategory (MVP + legacy no-MVP + "otro").
    List<String> serviceCategoryEnum = com.fixy.backend.model.ServiceCategory.ALL_IDS_INCLUDING_OTRO;
    List<String> urgencyEnum = List.of("alta", "media", "baja");
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "leadType", Map.of("type", "string", "enum", leadTypeEnum),
            "serviceCategory", Map.of("type", "string", "enum", serviceCategoryEnum),
            "area", Map.of("type", "string", "enum", CANONICAL_AREAS),
            "urgency", Map.of("type", "string", "enum", urgencyEnum),
            "summary", Map.of("type", "string"),
            "missingFields", Map.of("type", "array", "items", Map.of("type", "string")),
            "suggestedReply", Map.of("type", "string")
        ),
        "required", List.of("leadType", "serviceCategory", "area", "urgency", "summary", "missingFields", "suggestedReply")
    );
  }

  /**
   * Normaliza el valor de área devuelto por el LLM contra el catálogo canónico
   * (case/tildes-insensitive), para el caso en que el modelo devuelva una variante fuera del
   * enum del schema (ej. "san jose de carrasco" sin tilde, "SOLYMAR" en mayúsculas) pese a la
   * instrucción del prompt. Defensa en profundidad: el schema + prompt ya restringen esto, pero
   * un LLM puede igual desviarse. Si no matchea ningún valor canónico, cae a "sin definir" en vez
   * de propagar texto libre no reconocido.
   */
  static String normalizeAreaValue(String rawArea) {
    if (rawArea == null || rawArea.isBlank()) {
      return "sin definir";
    }
    String normalized = stripAccents(rawArea.toLowerCase(Locale.ROOT).trim());
    if ("sin definir".equals(normalized)) {
      return "sin definir";
    }
    java.util.Optional<com.fixy.backend.model.CoverageZone> canonical =
        com.fixy.backend.model.CoverageZone.fromLabel(normalized);
    if (canonical.isPresent()) {
      return canonical.get().label();
    }
    if ("canelones".equals(normalized)) {
      return "Ciudad de la Costa";
    }
    return "sin definir";
  }

  private static String stripAccents(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
  }

  /**
   * Construye el body de /responses. Los modelos gpt-5 son reasoning models: por default
   * usan "reasoning effort" medio, lo que agrega latencia de razonamiento invisible antes de
   * responder. Para un intake conversacional de baja latencia fijamos "low" explícitamente.
   * gpt-4.1 y anteriores ignoran/no tienen este campo si se omite, así que solo se agrega
   * cuando el modelo es de la familia gpt-5.
   */
  static Map<String, Object> buildResponsesPayload(String model, String prompt) {
    if (model != null && model.toLowerCase(Locale.ROOT).startsWith("gpt-5")) {
      return Map.of(
          "model", model,
          "input", prompt,
          "reasoning", Map.of("effort", "low")
      );
    }
    return Map.of(
        "model", model,
        "input", prompt
    );
  }

  private String extractText(JsonNode root, JsonNode firstOutput) {
    JsonNode outputText = root.path("output_text");
    if (outputText.isTextual()) {
      return outputText.asText();
    }

    if (firstOutput != null && firstOutput.has("content") && firstOutput.get("content").isArray()) {
      for (JsonNode item : firstOutput.get("content")) {
        if (item.has("text")) {
          return item.get("text").asText();
        }
      }
    }

    return null;
  }

  private List<String> readMissingFields(JsonNode node) {
    List<String> fields = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(item -> fields.add(item.asText()));
    }
    return fields;
  }

  private IntakeResponse classifyHeuristically(IntakeRequest request) {
    String message = request.message().toLowerCase(Locale.ROOT);
    String leadType = detectLeadType(message);
    String service = resolvedService(request, message);
    String area = resolvedArea(request, message);
    String urgency = resolvedUrgency(request, message);
    List<String> missingFields = detectMissingFields(request, message);

    return new IntakeResponse(
        leadType,
        service,
        area,
        urgency,
        buildSummary(request, service),
        missingFields,
        buildSuggestedReply(request, missingFields),
        "heuristic"
    );
  }

  private IntakeResponse applyStructuredFields(IntakeRequest request, IntakeResponse response) {
    String message = request.message().toLowerCase(Locale.ROOT);
    String service = hasText(request.serviceCategory())
        ? normalizeServiceCategory(request.serviceCategory())
        : response.serviceCategory();
    String area = hasText(request.zone())
        ? request.zone().trim()
        : response.area();
    String urgency = hasText(request.urgency())
        ? normalizeUrgency(request.urgency())
        : response.urgency();
    List<String> missingFields = normalizeMissingFields(request, response.missingFields(), message, service, area);

    return new IntakeResponse(
        response.leadType(),
        hasText(service) ? service : "otro",
        hasText(area) ? area : "sin definir",
        hasText(urgency) ? urgency : "media",
        buildSummary(request, hasText(service) ? service : response.serviceCategory()),
        missingFields,
        buildSuggestedReply(request, missingFields),
        response.agentSource()
    );
  }

  private String detectLeadType(String message) {
    if (message.contains("quiero unirme") || message.contains("soy proveedor") || message.contains("trabajo en")
        || message.contains("ofrezco") || message.contains("soy plomero") || message.contains("soy electricista")) {
      return "proveedor";
    }
    return "cliente";
  }

  /** Deriva del catálogo único ServiceCategory (ver su javadoc). */
  private String detectService(String message) {
    return com.fixy.backend.model.ServiceCategory.detectFromText(message)
        .map(com.fixy.backend.model.ServiceCategory::id)
        .orElse("otro");
  }

  private String resolvedService(IntakeRequest request, String message) {
    if (hasText(request.serviceCategory())) {
      return normalizeServiceCategory(request.serviceCategory());
    }
    return detectService(message);
  }

  private String detectArea(String message) {
    String normalized = stripAccents(message.toLowerCase(Locale.ROOT));
    for (Map.Entry<String, String> entry : ZONE_TOKENS.entrySet()) {
      if (normalized.contains(stripAccents(entry.getKey()))) {
        return toDisplayArea(entry.getValue());
      }
    }
    if (normalized.contains("ciudad de la costa") || normalized.contains("canelones")) {
      return "Ciudad de la Costa";
    }
    return "sin definir";
  }

  /**
   * Zona detectada SOLO del texto del mensaje (sin el passthrough de la zona
   * ya conocida que hace resolvedArea), o null si el mensaje no menciona
   * ninguna. Para las correcciones del cliente en el fallback heurístico
   * ("no, es en Lagomar") — ver LeadAgentService.respondWithHeuristicFallback.
   */
  String areaMentionedIn(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    String area = detectArea(message);
    return "sin definir".equals(area) ? null : area;
  }

  private String resolvedArea(IntakeRequest request, String message) {
    if (hasText(request.zone())) {
      return request.zone().trim();
    }
    return detectArea(message);
  }

  private String detectUrgency(String message) {
    if (containsAny(message, "urgente", "ya", "ahora", "sin parar", "inund", "chispa", "corto")) {
      return "alta";
    }
    if (containsAny(message, "hoy", "cuanto antes", "cuanto antes posible")) {
      return "media";
    }
    return "baja";
  }

  private String resolvedUrgency(IntakeRequest request, String message) {
    if (hasText(request.urgency())) {
      return normalizeUrgency(request.urgency());
    }
    return detectUrgency(message);
  }

  /** true si el mensaje menciona alguna zona reconocida (misma fuente que
   * {@link #detectArea}, sin duplicar la lista de tokens de zonas). */
  private boolean mentionsAnyZone(String message) {
    return !"sin definir".equals(detectArea(message));
  }

  private List<String> detectMissingFields(IntakeRequest request, String message) {
    List<String> fields = new ArrayList<>();
    if (!hasText(request.zone()) && !mentionsAnyZone(message)) {
      fields.add("zona");
    }
    if (!containsAny(message, "foto", "imagen")) {
      fields.add("foto del problema");
    }
    if (!hasText(request.address()) && !containsAny(message, "direccion", "dirección", "calle", "esquina", "referencia")) {
      fields.add("direccion exacta");
    }
    return fields;
  }

  private List<String> normalizeMissingFields(
      IntakeRequest request,
      List<String> missingFields,
      String message,
      String service,
      String area
  ) {
    List<String> normalized = new ArrayList<>(missingFields == null ? List.of() : missingFields);

    if (!hasText(area) || "sin definir".equalsIgnoreCase(area)) {
      if (!hasText(request.zone()) && !mentionsAnyZone(message)) {
        normalized.add("zona");
      }
    }

    if (!hasText(service) || "otro".equalsIgnoreCase(service)) {
      if (!hasText(request.serviceCategory())) {
        normalized.add("categoria");
      }
    }

    return normalized.stream()
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .filter(value -> !("zona".equalsIgnoreCase(value) && hasText(request.zone())))
        .filter(value -> !("categoria".equalsIgnoreCase(value) && hasText(request.serviceCategory())))
        .filter(value -> !("direccion exacta".equalsIgnoreCase(value) && hasText(request.address())))
        .distinct()
        .toList();
  }

  private String buildSummary(IntakeRequest request, String service) {
    String contact = safe(request.contactName()).isBlank() ? "contacto sin nombre" : request.contactName();
    return "Problema de %s reportado por %s".formatted(service, contact);
  }

  private String buildSuggestedReply(IntakeRequest request, List<String> missingFields) {
    if (detectLeadType(request.message().toLowerCase(Locale.ROOT)).equals("proveedor")) {
      return "Gracias por escribir. Comparteme tu rubro, la zona donde trabajas y experiencia para evaluarte como proveedor de Fixy.";
    }
    if (missingFields.contains("direccion exacta")) {
      return "Gracias, ya recibimos tu caso. Compartenos la direccion exacta para coordinar mas rapido.";
    }
    if (missingFields.contains("foto del problema")) {
      return "Gracias, ya recibimos tu caso. Si puedes, agrega una foto para que el proveedor entienda mejor el problema.";
    }
    return "Gracias, ya tenemos los datos principales. El siguiente paso es coordinar proveedor disponible para tu zona.";
  }

  private boolean containsAny(String text, String... candidates) {
    for (String candidate : candidates) {
      if (text.contains(candidate)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Etiqueta canónica de una zona escrita como venga (mayúsculas, con o sin
   * tilde). Era un switch con las 11 zonas escritas a mano — la quinta copia
   * del mismo catálogo; ahora deriva de {@link com.fixy.backend.model.CoverageZone}.
   * Lo que no reconoce cae al paraguas "Ciudad de la Costa", igual que antes.
   */
  private static String toDisplayArea(String zone) {
    return com.fixy.backend.model.CoverageZone.fromLabel(zone)
        .map(com.fixy.backend.model.CoverageZone::label)
        .orElse("Ciudad de la Costa");
  }

  private String normalizeServiceCategory(String serviceCategory) {
    String normalized = serviceCategory.toLowerCase(Locale.ROOT).trim();
    if (containsAny(normalized, "plomer", "agua", "caño", "cano")) {
      return "plomeria";
    }
    if (containsAny(normalized, "electric", "luz")) {
      return "electricidad";
    }
    if (containsAny(normalized, "cerraj", "llave", "cerradura")) {
      return "cerrajeria";
    }
    if (containsAny(normalized, "barometr")) {
      return "barometrica";
    }
    if (containsAny(normalized, "jardin", "jardín", "pasto", "cesped", "césped", "jardiner")) {
      return "jardineria";
    }
    if (containsAny(normalized, "aire acondicionado", "aires acondicionados", "split", "climatizacion", "climatización", "refrigeracion", "refrigeración")) {
      return "aires_acondicionados";
    }
    if (containsAny(normalized, "torta", "cumpleaños", "cumpleanos", "cupcake", "pasteleria", "pastelería", "reposteria", "repostería")) {
      return "pasteleria";
    }
    if (containsAny(normalized, "decoracion", "decoración", "ambientacion", "ambientación", "globos")) {
      return "decoracion_fiestas";
    }
    return normalized.isBlank() ? "otro" : normalized;
  }

  private String normalizeUrgency(String urgency) {
    String normalized = urgency.toLowerCase(Locale.ROOT).trim();
    if (containsAny(normalized, "alta", "urgente", "ya", "ahora")) {
      return "alta";
    }
    if (containsAny(normalized, "media", "hoy", "pronto")) {
      return "media";
    }
    return "baja";
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
