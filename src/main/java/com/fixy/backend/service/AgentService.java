package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.domain.CategoryDef;
import com.fixy.backend.domain.DomainCatalog;
import com.fixy.backend.domain.ZoneDef;
import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

  private static final Logger log = LoggerFactory.getLogger(AgentService.class);

  // El catálogo de zonas y categorías vive en domain/home-services.yml (fuente única,
  // ver DomainCatalog). Acá se consulta con DomainCatalog.get(): zoneByLabel normaliza
  // mayúsculas y tildes, y detectZone recorre las zonas en el orden de prioridad del YAML
  // (específicas antes que "solymar" a secas, caso real lead #132: "montes" cayó a
  // "Ciudad de la Costa").

  private static final String INTAKE_PROMPT_TEMPLATE = PromptLoader.loadFormatTemplate("prompts/intake-classifier.md");

  private final ObjectMapper objectMapper;
  private final LlmGateway llmGateway;

  /**
   * Constructor legacy (3 args), usado por tests existentes que no necesitan multi-proveedor
   * (siempre cayeron a heurística con apiKey=""). Mantiene compatibilidad binaria: equivale a
   * provider="openai" sin credenciales de Cloudflare. No es el constructor que usa Spring (ver
   * {@link #AgentService(ObjectMapper, LlmGateway)}): arma su PROPIO LlmGateway, igual que antes
   * armaba sus propios WebClients — no comparte el bean con LeadAgentService, pero tampoco lo hacía
   * antes de este refactor (cada instancia manual seguía siendo independiente).
   */
  public AgentService(ObjectMapper objectMapper, String openAiApiKey, String openAiModel) {
    this(objectMapper, openAiApiKey, openAiModel, "openai", "", "", "");
  }

  /**
   * Constructor legacy (7 args) usado directamente por tests con proveedor explícito
   * (ver AgentServiceWorkersAiPayloadTest). Mismo motivo que el de 3 args: arma su propio
   * LlmGateway en vez de recibir el bean compartido.
   */
  public AgentService(
      ObjectMapper objectMapper,
      String openAiApiKey,
      String openAiModel,
      String provider,
      String cloudflareAccountId,
      String cloudflareApiToken,
      String cloudflareModel
  ) {
    this(objectMapper, new LlmGateway(
        objectMapper, openAiApiKey, openAiModel, true, provider,
        "http://127.0.0.1:11434", "qwen2.5:3b",
        cloudflareAccountId, cloudflareApiToken, cloudflareModel));
  }

  /** Constructor real usado por Spring: comparte el único LlmGateway de la app (Core Fase 1,
   * ver CORE_FASE1_CONTRATO.md) en vez de armar sus propios WebClients. */
  @Autowired
  public AgentService(ObjectMapper objectMapper, LlmGateway llmGateway) {
    this.objectMapper = objectMapper;
    this.llmGateway = llmGateway;
  }

  public IntakeResponse classify(IntakeRequest request) {
    IntakeResponse response = null;
    if ("workersai".equals(llmGateway.provider()) && llmGateway.hasCloudflareCredentials()) {
      response = classifyWithWorkersAi(request);
      if (response == null) {
        log.warn("workersai classify call failed or returned null, degrading to heuristic");
      }
    } else if (llmGateway.hasOpenAiKey()) {
      response = classifyWithOpenAi(request);
    }

    if (response == null) {
      response = classifyHeuristically(request);
    }

    return applyStructuredFields(request, response);
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
      String text = llmGateway.completeTextOpenAi(prompt);

      if (text == null || text.isBlank()) {
        return null;
      }

      JsonNode result = objectMapper.readTree(text);
      return new IntakeResponse(
          result.path("leadType").asText("cliente"),
          DomainCatalog.get().refineCategoryId(request.message(), result.path("serviceCategory").asText("otro")),
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
      // 1 solo retry ante timeout/error transitorio (latencia de CF es variable). Si vuelve a
      // fallar, el catch de abajo degrada a heurística — no vale la pena reintentar más veces
      // para un intake conversacional de baja latencia. (timeout 30s, max_tokens 400: ver
      // LlmGateway.completeJsonWorkersAi).
      String raw = llmGateway.completeJsonWorkersAi(prompt, intakeJsonSchema());

      JsonNode result = parseWorkersAiResult(objectMapper, raw);
      if (result == null) {
        return null;
      }

      return new IntakeResponse(
          result.path("leadType").asText("cliente"),
          DomainCatalog.get().refineCategoryId(request.message(), result.path("serviceCategory").asText("otro")),
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
    // Fuente única: DomainCatalog (MVP + legacy no-MVP + "otro").
    DomainCatalog catalog = DomainCatalog.get();
    List<String> serviceCategoryEnum = catalog.allCategoryIds();
    // Valores canónicos de "area" (display) + "sin definir", usados como enum estricto en el
    // json_schema de Cloudflare Workers AI para que el modelo no devuelva texto libre.
    List<String> areaEnum = java.util.stream.Stream.concat(
        catalog.promptZones().stream().map(ZoneDef::label), java.util.stream.Stream.of(catalog.areaUnknown())).toList();
    List<String> urgencyEnum = List.of("alta", "media", "baja");
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "leadType", Map.of("type", "string", "enum", leadTypeEnum),
            "serviceCategory", Map.of("type", "string", "enum", serviceCategoryEnum),
            "area", Map.of("type", "string", "enum", areaEnum),
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
      return DomainCatalog.get().areaUnknown();
    }
    String normalized = stripAccents(rawArea.toLowerCase(Locale.ROOT).trim());
    DomainCatalog catalog = DomainCatalog.get();
    if (DomainCatalog.normalize(catalog.areaUnknown()).equals(normalized)) {
      return catalog.areaUnknown();
    }
    java.util.Optional<ZoneDef> canonical = catalog.zoneByLabel(normalized);
    if (canonical.isPresent()) {
      return canonical.get().label();
    }
    // Valor genérico del paraguas sin barrio (ej. "canelones"): tokens de detección del paraguas.
    return catalog.umbrellaByDetectToken(normalized).map(ZoneDef::label).orElse(catalog.areaUnknown());
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

  /** Deriva del catálogo único DomainCatalog (domain/home-services.yml). */
  private String detectService(String message) {
    return DomainCatalog.get().detectCategory(message)
        .map(CategoryDef::id)
        .orElse("otro");
  }

  private String resolvedService(IntakeRequest request, String message) {
    if (hasText(request.serviceCategory())) {
      return normalizeServiceCategory(request.serviceCategory());
    }
    return detectService(message);
  }

  private String detectArea(String message) {
    DomainCatalog catalog = DomainCatalog.get();
    return catalog.detectZone(message).map(ZoneDef::label).orElse(catalog.areaUnknown());
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
    return DomainCatalog.get().areaUnknown().equals(area) ? null : area;
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
    return !DomainCatalog.get().areaUnknown().equals(detectArea(message));
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
   * Categoría ya declarada por el usuario (no texto libre): usa la lista deliberadamente más
   * CORTA {@code declaredKeywords} del catálogo — no deriva de {@code keywords} a propósito,
   * para no ampliar sus matches y arriesgar falsos positivos. Mismo orden de evaluación que
   * antes (el de declaración del catálogo).
   */
  private String normalizeServiceCategory(String serviceCategory) {
    String normalized = serviceCategory.toLowerCase(Locale.ROOT).trim();
    for (CategoryDef category : DomainCatalog.get().categories()) {
      if (!category.declaredKeywords().isEmpty()
          && containsAny(normalized, category.declaredKeywords().toArray(String[]::new))) {
        return category.id();
      }
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
