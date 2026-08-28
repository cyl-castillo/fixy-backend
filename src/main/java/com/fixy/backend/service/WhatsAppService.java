package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Envia mensajes a proveedores y clientes via WhatsApp Cloud API (Meta) o un
 * BSP compatible (ej. 360dialog) — el body/formato de mensajes e interactivos
 * es el mismo en ambos, lo unico que cambia es el path del endpoint y el
 * esquema de autenticacion, asi que eso es lo unico configurable.
 *
 * Config requerida:
 *   fixy.whatsapp.phone-number-id   (id del numero Fixy; con BSPs puede no
 *                                    usarse en el path, pero igual habilita
 *                                    el servicio)
 *   fixy.whatsapp.access-token      (token de Meta, o API key del BSP)
 *   fixy.whatsapp.api-version       (default v21.0, solo aplica a Meta)
 *   fixy.whatsapp.auth-scheme       (bearer [default, Meta] | d360-api-key
 *                                    [360dialog])
 *   fixy.whatsapp.messages-path     (override del path; vacio = calcula el
 *                                    de Meta. 360dialog sandbox usa
 *                                    "/v1/messages")
 *
 * Si las credenciales no estan seteadas, el servicio queda en estado "disabled"
 * y los metodos logean warning sin hacer la llamada. Eso permite tener el codigo
 * cableado en prod antes de que Carlos termine el setup Meta.
 */
@Service
public class WhatsAppService {

  private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);

  private final WebClient client;
  private final ObjectMapper objectMapper;
  private final String phoneNumberId;
  private final String accessToken;
  private final String apiVersion;
  private final String authScheme;
  private final String messagesPathOverride;
  private final boolean enabled;

  public WhatsAppService(
      ObjectMapper objectMapper,
      @Value("${fixy.whatsapp.phone-number-id:}") String phoneNumberId,
      @Value("${fixy.whatsapp.access-token:}") String accessToken,
      @Value("${fixy.whatsapp.api-version:v21.0}") String apiVersion,
      @Value("${fixy.whatsapp.base-url:https://graph.facebook.com}") String baseUrl,
      @Value("${fixy.whatsapp.auth-scheme:bearer}") String authScheme,
      @Value("${fixy.whatsapp.messages-path:}") String messagesPathOverride
  ) {
    this.objectMapper = objectMapper;
    this.phoneNumberId = phoneNumberId;
    this.accessToken = accessToken;
    this.apiVersion = apiVersion;
    this.authScheme = authScheme == null ? "bearer" : authScheme.trim().toLowerCase(Locale.ROOT);
    this.messagesPathOverride = messagesPathOverride;
    this.enabled = phoneNumberId != null && !phoneNumberId.isBlank()
        && accessToken != null && !accessToken.isBlank();
    this.client = WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    log.info("WhatsAppService initialized: enabled={} apiVersion={} authScheme={}",
        enabled, apiVersion, this.authScheme);
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Envia texto libre. Solo funciona si el destinatario nos escribio en
   * las ultimas 24h (ventana de servicio). Si esta fuera de ventana, Meta
   * responde error y hay que usar sendTemplate.
   */
  public boolean sendText(String toRaw, String text) {
    if (!enabled) {
      log.warn("whatsapp disabled, skip sendText to {}", toRaw);
      return false;
    }
    String to = normalize(toRaw);
    if (to == null) {
      log.warn("whatsapp sendText: invalid number {}", toRaw);
      return false;
    }
    Map<String, Object> body = Map.of(
        "messaging_product", "whatsapp",
        "to", to,
        "type", "text",
        "text", Map.of("preview_url", false, "body", text)
    );
    return post(body, "sendText to=" + to);
  }

  /**
   * Envia un template aprobado (necesario para iniciar conversaciones).
   *
   * @param toRaw       numero destino, formato local o E.164
   * @param templateName nombre exacto del template (case-sensitive)
   * @param languageCode codigo idioma (ej "es", "es_AR")
   * @param parameters   valores para los placeholders {{1}}, {{2}}, ...
   */
  public boolean sendTemplate(String toRaw, String templateName, String languageCode, List<String> parameters) {
    if (!enabled) {
      log.warn("whatsapp disabled, skip sendTemplate to {}", toRaw);
      return false;
    }
    String to = normalize(toRaw);
    if (to == null) {
      log.warn("whatsapp sendTemplate: invalid number {}", toRaw);
      return false;
    }
    List<Map<String, Object>> params = new ArrayList<>();
    if (parameters != null) {
      for (String p : parameters) {
        params.add(Map.of("type", "text", "text", p == null ? "" : p));
      }
    }
    List<Map<String, Object>> components = params.isEmpty()
        ? List.of()
        : List.of(Map.of("type", "body", "parameters", params));
    Map<String, Object> template = new java.util.HashMap<>();
    template.put("name", templateName);
    template.put("language", Map.of("code", languageCode == null ? "es" : languageCode));
    if (!components.isEmpty()) template.put("components", components);
    Map<String, Object> body = Map.of(
        "messaging_product", "whatsapp",
        "to", to,
        "type", "template",
        "template", template
    );
    return post(body, "sendTemplate " + templateName + " to=" + to);
  }

  /** Una fila de una interactive list: id (lo que vuelve en el webhook al
   * elegirla), title (≤24 caracteres) y description opcional (≤72 caracteres),
   * límites de Meta Cloud API. */
  public record ListRow(String id, String title, String description) {}

  /**
   * Envia una interactive list message: un botón que despliega hasta 10 filas
   * agrupadas en una sección. Usado por WhatsAppMenuService para el menú de
   * apertura. Como sendText/sendTemplate, es user-initiated-only si se manda
   * en respuesta a un mensaje entrante — no necesita template aprobado.
   */
  public boolean sendInteractiveList(String toRaw, String bodyText, String buttonLabel,
      String sectionTitle, List<ListRow> rows) {
    if (!enabled) {
      log.warn("whatsapp disabled, skip sendInteractiveList to {}", toRaw);
      return false;
    }
    String to = normalize(toRaw);
    if (to == null) {
      log.warn("whatsapp sendInteractiveList: invalid number {}", toRaw);
      return false;
    }
    List<Map<String, Object>> rowMaps = new ArrayList<>();
    for (ListRow row : rows) {
      Map<String, Object> rowMap = new java.util.HashMap<>();
      rowMap.put("id", row.id());
      rowMap.put("title", row.title());
      if (row.description() != null && !row.description().isBlank()) {
        rowMap.put("description", row.description());
      }
      rowMaps.add(rowMap);
    }
    Map<String, Object> section = Map.of("title", sectionTitle, "rows", rowMaps);
    Map<String, Object> action = Map.of("button", buttonLabel, "sections", List.of(section));
    Map<String, Object> interactive = Map.of(
        "type", "list",
        "body", Map.of("text", bodyText),
        "action", action
    );
    Map<String, Object> body = Map.of(
        "messaging_product", "whatsapp",
        "to", to,
        "type", "interactive",
        "interactive", interactive
    );
    return post(body, "sendInteractiveList to=" + to);
  }

  /** Path del endpoint de mensajes. Meta: /{apiVersion}/{phoneNumberId}/messages.
   * Un BSP (ej. 360dialog: "/v1/messages" en sandbox) expone un path fijo
   * distinto — se sobreescribe con fixy.whatsapp.messages-path en vez de
   * asumir un unico formato de BSP acá adentro. */
  String messagesPath() {
    return (messagesPathOverride == null || messagesPathOverride.isBlank())
        ? "/" + apiVersion + "/" + phoneNumberId + "/messages"
        : messagesPathOverride;
  }

  /** Nombre y valor del header de autenticacion segun fixy.whatsapp.auth-scheme. */
  String[] authHeader() {
    if ("d360-api-key".equals(authScheme)) {
      return new String[] { "D360-API-KEY", accessToken };
    }
    return new String[] { HttpHeaders.AUTHORIZATION, "Bearer " + accessToken };
  }

  private boolean post(Map<String, Object> body, String contextDescription) {
    try {
      String[] auth = authHeader();
      String response = client.post()
          .uri(messagesPath())
          .header(auth[0], auth[1])
          .bodyValue(body)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(15))
          .block();
      if (response == null) {
        log.warn("whatsapp {}: empty response", contextDescription);
        return false;
      }
      JsonNode root = objectMapper.readTree(response);
      JsonNode messages = root.path("messages");
      if (messages.isArray() && messages.size() > 0) {
        log.info("whatsapp {} OK id={}", contextDescription, messages.get(0).path("id").asText());
        return true;
      }
      log.warn("whatsapp {} no-message in response: {}", contextDescription,
          response.length() > 300 ? response.substring(0, 300) : response);
      return false;
    } catch (Exception ex) {
      log.warn("whatsapp {} failed: {}", contextDescription, ex.getMessage());
      return false;
    }
  }

  /**
   * Normaliza a formato E.164 sin "+". Acepta:
   *  - "099429328"   → "59899429328" (asume UY si arranca con 0)
   *  - "+59899429328" → "59899429328"
   *  - "59899429328" → "59899429328"
   *  - "099 429 328" → "59899429328"
   */
  static String normalize(String raw) {
    if (raw == null) return null;
    String digits = raw.replaceAll("[^0-9]", "");
    if (digits.isEmpty()) return null;
    if (digits.startsWith("0")) {
      digits = digits.substring(1);
      if (digits.length() < 8) return null;
      return "598" + digits;
    }
    if (digits.startsWith("598")) {
      return digits;
    }
    if (digits.length() == 8 || digits.length() == 9) {
      // Asumimos UY sin 0 ni 598.
      return "598" + digits;
    }
    return digits;
  }

  public static String denormalize(String e164) {
    if (e164 == null) return null;
    String digits = e164.replaceAll("[^0-9]", "");
    if (digits.startsWith("598")) {
      String rest = digits.substring(3);
      return "0" + rest;
    }
    return digits;
  }
}
