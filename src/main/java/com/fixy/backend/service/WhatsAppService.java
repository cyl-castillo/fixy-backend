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
 * Envia mensajes a proveedores y clientes via WhatsApp Cloud API (Meta).
 *
 * Config requerida:
 *   fixy.whatsapp.phone-number-id   (id del numero Fixy registrado en Cloud API)
 *   fixy.whatsapp.access-token      (system user token, never expires)
 *   fixy.whatsapp.api-version       (default v21.0)
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
  private final boolean enabled;

  public WhatsAppService(
      ObjectMapper objectMapper,
      @Value("${fixy.whatsapp.phone-number-id:}") String phoneNumberId,
      @Value("${fixy.whatsapp.access-token:}") String accessToken,
      @Value("${fixy.whatsapp.api-version:v21.0}") String apiVersion,
      @Value("${fixy.whatsapp.base-url:https://graph.facebook.com}") String baseUrl
  ) {
    this.objectMapper = objectMapper;
    this.phoneNumberId = phoneNumberId;
    this.accessToken = accessToken;
    this.apiVersion = apiVersion;
    this.enabled = phoneNumberId != null && !phoneNumberId.isBlank()
        && accessToken != null && !accessToken.isBlank();
    this.client = WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    log.info("WhatsAppService initialized: enabled={} apiVersion={}", enabled, apiVersion);
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

  private boolean post(Map<String, Object> body, String contextDescription) {
    try {
      String uri = "/" + apiVersion + "/" + phoneNumberId + "/messages";
      String response = client.post()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
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

  String unusedLocaleHint() {
    // Solo para silenciar warnings de imports no usados en algunos compiladores.
    return Locale.ROOT.toString();
  }
}
