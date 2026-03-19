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
    if (!openAiApiKey.isBlank()) {
      IntakeResponse aiResponse = classifyWithOpenAi(request);
      if (aiResponse != null) {
        return aiResponse;
      }
    }

    return classifyHeuristically(request);
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
        Mensaje: %s
        """.formatted(
        safe(request.contactName()),
        safe(request.phone()),
        safe(request.channel()),
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
          result.path("suggestedReply").asText(buildSuggestedReply(request.message())),
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
    String service = detectService(message);
    String area = detectArea(message);
    String urgency = detectUrgency(message);
    List<String> missingFields = detectMissingFields(message);

    return new IntakeResponse(
        leadType,
        service,
        area,
        urgency,
        buildSummary(request, service),
        missingFields,
        buildSuggestedReply(message),
        "heuristic"
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

  private String detectUrgency(String message) {
    if (containsAny(message, "urgente", "ya", "ahora", "sin parar", "inund", "chispa", "corto")) {
      return "alta";
    }
    if (containsAny(message, "hoy", "cuanto antes", "cuanto antes posible")) {
      return "media";
    }
    return "baja";
  }

  private List<String> detectMissingFields(String message) {
    List<String> fields = new ArrayList<>();
    if (!containsAny(message, "montevideo", "centro", "cordon", "cordón", "pocitos", "punta carretas",
        "carrasco", "malvin", "malvín", "buceo", "union", "unión", "parque batlle", "tres cruces",
        "cerro", "la blanqueada", "prado", "sayago", "belvedere", "villa espanola", "villa española")) {
      fields.add("zona");
    }
    if (!containsAny(message, "foto", "imagen")) {
      fields.add("foto del problema");
    }
    fields.add("direccion exacta");
    return fields;
  }

  private String buildSummary(IntakeRequest request, String service) {
    String contact = safe(request.contactName()).isBlank() ? "contacto sin nombre" : request.contactName();
    return "Problema de %s reportado por %s".formatted(service, contact);
  }

  private String buildSuggestedReply(String message) {
    if (detectLeadType(message).equals("proveedor")) {
      return "Gracias por escribir. Comparteme tu rubro, la zona donde trabajas y experiencia para evaluarte como proveedor de Fixy.";
    }
    return "Gracias, ya estamos revisando tu caso. Compartenos direccion exacta y, si puedes, una foto para coordinar mas rapido.";
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

  private String safe(String value) {
    return value == null ? "" : value;
  }
}
