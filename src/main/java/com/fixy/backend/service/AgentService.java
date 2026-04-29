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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class AgentService {

  private static final List<String> MONTEVIDEO_ZONES = List.of(
      "centro", "cordon", "cordón", "pocitos", "punta carretas", "carrasco", "malvin", "malvín",
      "buceo", "union", "unión", "parque batlle", "tres cruces", "cerro", "la blanqueada",
      "prado", "sayago", "belvedere", "villa espanola", "villa española"
  );

  private final ObjectMapper objectMapper;
  private final WebClient webClient;
  private final String openAiApiKey;
  private final String openAiModel;

  public AgentService(
      ObjectMapper objectMapper,
      @Value("${fixy.openai.api-key:}") String openAiApiKey,
      @Value("${fixy.openai.model:gpt-4.1-mini}") String openAiModel
  ) {
    this.objectMapper = objectMapper;
    this.openAiApiKey = openAiApiKey;
    this.openAiModel = openAiModel;
    this.webClient = WebClient.builder()
        .baseUrl("https://api.openai.com/v1")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
  }

  public IntakeResponse classify(IntakeRequest request) {
    IntakeResponse response = null;
    if (!openAiApiKey.isBlank()) {
      response = classifyWithOpenAi(request);
    }

    if (response == null) {
      response = classifyHeuristically(request);
    }

    return applyStructuredFields(request, response);
  }

  private IntakeResponse classifyWithOpenAi(IntakeRequest request) {
    String prompt = """
        Eres el agente de intake de Fixy.
        Fixy opera primero en Montevideo, Uruguay.
        Analiza el mensaje y devuelve solo JSON con estas claves:
        leadType, serviceCategory, area, urgency, summary, missingFields, suggestedReply.
        Usa valores en espanol minusculas simples.
        leadType debe ser cliente o proveedor.
        serviceCategory debe ser uno de: plomeria, electricidad, cerrajeria, barometrica, reparaciones, otro.
        urgency debe ser: alta, media o baja.
        missingFields debe ser array de strings.
        suggestedReply debe ser corto, natural y util.

        Nombre: %s
        Telefono: %s
        Canal: %s
        Servicio elegido: %s
        Zona elegida: %s
        Urgencia elegida: %s
        Direccion o referencia: %s
        Detalle adicional: %s
        Mensaje: %s
        """.formatted(
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
          "model", openAiModel,
          "input", prompt
      );

      String raw = webClient.post()
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
      JsonNode outputText = root.path("output").isArray() ? root.path("output").get(0) : null;
      String text = extractText(root, outputText);

      if (text == null || text.isBlank()) {
        return null;
      }

      JsonNode result = objectMapper.readTree(text);
      return new IntakeResponse(
          result.path("leadType").asText("cliente"),
          result.path("serviceCategory").asText("otro"),
          result.path("area").asText(detectArea(request.message())),
          result.path("urgency").asText("media"),
          result.path("summary").asText(buildSummary(request, detectService(request.message()))),
          readMissingFields(result.path("missingFields")),
          result.path("suggestedReply").asText(buildSuggestedReply(request, readMissingFields(result.path("missingFields")))),
          "openai"
      );
    } catch (Exception ignored) {
      return null;
    }
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

  private String detectService(String message) {
    if (containsAny(message, "agua", "canilla", "ducha", "caño", "cano", "perdida", "pierde", "plomer")) {
      return "plomeria";
    }
    if (containsAny(message, "luz", "corriente", "enchufe", "tablero", "corto", "electric")) {
      return "electricidad";
    }
    if (containsAny(message, "llave", "cerradura", "tranca", "me quede afuera", "me quede fuera", "cerraj")) {
      return "cerrajeria";
    }
    if (containsAny(message, "pozo", "barometr", "camara septica", "cámara séptica")) {
      return "barometrica";
    }
    if (containsAny(message, "arreglo", "reparacion", "reparación", "hogar", "mueble", "persiana")) {
      return "reparaciones";
    }
    return "otro";
  }

  private String resolvedService(IntakeRequest request, String message) {
    if (hasText(request.serviceCategory())) {
      return normalizeServiceCategory(request.serviceCategory());
    }
    return detectService(message);
  }

  private String detectArea(String message) {
    String normalized = message.toLowerCase(Locale.ROOT);
    for (String zone : MONTEVIDEO_ZONES) {
      if (normalized.contains(zone)) {
        return toDisplayArea(zone);
      }
    }
    if (normalized.contains("montevideo")) {
      return "Montevideo";
    }
    return "sin definir";
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

  private List<String> detectMissingFields(IntakeRequest request, String message) {
    List<String> fields = new ArrayList<>();
    if (!hasText(request.zone()) && !containsAny(message, "montevideo", "centro", "cordon", "cordón", "pocitos", "punta carretas",
        "carrasco", "malvin", "malvín", "buceo", "union", "unión", "parque batlle", "tres cruces",
        "cerro", "la blanqueada", "prado", "sayago", "belvedere", "villa espanola", "villa española")) {
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
      if (!hasText(request.zone()) && !containsAny(message, "montevideo", "centro", "cordon", "cordón", "pocitos",
          "punta carretas", "carrasco", "malvin", "malvín", "buceo", "union", "unión")) {
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

  private String toDisplayArea(String zone) {
    return switch (zone) {
      case "belvedere" -> "Belvedere";
      case "buceo" -> "Buceo";
      case "carrasco" -> "Carrasco";
      case "centro" -> "Centro";
      case "cerro" -> "Cerro";
      case "cordon", "cordón" -> "Cordón";
      case "la blanqueada" -> "La Blanqueada";
      case "malvin", "malvín" -> "Malvín";
      case "parque batlle" -> "Parque Batlle";
      case "pocitos" -> "Pocitos";
      case "prado" -> "Prado";
      case "punta carretas" -> "Punta Carretas";
      case "sayago" -> "Sayago";
      case "tres cruces" -> "Tres Cruces";
      case "union", "unión" -> "Unión";
      case "villa espanola", "villa española" -> "Villa Española";
      default -> "Montevideo";
    };
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
    if (containsAny(normalized, "repar")) {
      return "reparaciones";
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
