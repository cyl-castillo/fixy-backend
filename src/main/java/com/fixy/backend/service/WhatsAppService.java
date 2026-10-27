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

  private static final int MAX_MEDIA_BYTES = 12 * 1024 * 1024;

  private final WebClient client;
  /** Para bajar media: el buffer por defecto de WebClient (256 KB) no alcanza para una foto. */
  private final WebClient mediaClient;
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
    this.mediaClient = WebClient.builder()
        .baseUrl(baseUrl)
        .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_MEDIA_BYTES))
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
    return sendTextReturningId(toRaw, text) != null;
  }

  /**
   * Igual que {@link #sendText}, pero devuelve el wamid ({@code messages[0].id}
   * de la respuesta de Meta) o {@code null} si no se envió. Ese id es la
   * única clave con la que Meta reporta después, por el webhook
   * ({@code statuses[]}), si el mensaje llegó o falló — quien lo necesite
   * para diagnosticar entregas lo persiste (ver
   * {@code LeadTimelineService.WHATSAPP_SENT}).
   */
  public String sendTextReturningId(String toRaw, String text) {
    if (!enabled) {
      log.warn("whatsapp disabled, skip sendText to {}", toRaw);
      return null;
    }
    String to = normalize(toRaw);
    if (to == null) {
      log.warn("whatsapp sendText: invalid number {}", toRaw);
      return null;
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
    return sendTemplateReturningId(toRaw, templateName, languageCode, parameters) != null;
  }

  /**
   * Igual que {@link #sendTemplate}, pero devuelve el wamid del mensaje
   * aceptado por Meta o {@code null} si no se envió. Motivación (2026-10-08):
   * la plantilla {@code provider_lead_offer} al técnico salió "OK id=wamid..."
   * en el log y nunca llegó al celular; sin el wamid guardado no había forma
   * de cruzar el {@code statuses[]} del webhook con el lead afectado.
   */
  public String sendTemplateReturningId(String toRaw, String templateName, String languageCode, List<String> parameters) {
    return sendTemplateReturningId(toRaw, templateName, languageCode, parameters, null);
  }

  /**
   * Plantilla con body y, opcionalmente, un botón URL con sufijo dinámico
   * (2026-10-13, {@code customer_order_update}: "Ver mi pedido" →
   * {@code https://www.fixy.com.uy/c/{{1}}}). {@code urlButtonSuffix} null o
   * vacío = sin componente de botón. Si la plantilla aprobada TIENE botón
   * dinámico, Meta exige el parámetro: omitirlo falla el envío; si NO lo
   * tiene, mandarlo también falla — por eso es configurable del lado del
   * caller (ver {@code CustomerWhatsAppNotifier}).
   */
  public String sendTemplateReturningId(String toRaw, String templateName, String languageCode,
      List<String> bodyParams, String urlButtonSuffix) {
    if (!enabled) {
      log.warn("whatsapp disabled, skip sendTemplate to {}", toRaw);
      return null;
    }
    String to = normalize(toRaw);
    if (to == null) {
      log.warn("whatsapp sendTemplate: invalid number {}", toRaw);
      return null;
    }
    return post(buildTemplateBody(to, templateName, languageCode, bodyParams, urlButtonSuffix),
        "sendTemplate " + templateName + " to=" + to);
  }

  /** Payload de {@code type=template} del Cloud API. Package-private y
   * estático para poder verificar el JSON exacto sin red. */
  static Map<String, Object> buildTemplateBody(String to, String templateName, String languageCode,
      List<String> bodyParams, String urlButtonSuffix) {
    List<Map<String, Object>> params = new ArrayList<>();
    if (bodyParams != null) {
      for (String p : bodyParams) {
        params.add(Map.of("type", "text", "text", p == null ? "" : p));
      }
    }
    List<Map<String, Object>> components = new ArrayList<>();
    if (!params.isEmpty()) {
      components.add(Map.of("type", "body", "parameters", params));
    }
    if (urlButtonSuffix != null && !urlButtonSuffix.isBlank()) {
      components.add(Map.of(
          "type", "button",
          "sub_type", "url",
          "index", "0",
          "parameters", List.of(Map.of("type", "text", "text", urlButtonSuffix))));
    }
    Map<String, Object> template = new java.util.HashMap<>();
    template.put("name", templateName);
    template.put("language", Map.of("code", languageCode == null ? "es" : languageCode));
    if (!components.isEmpty()) template.put("components", components);
    return Map.of(
        "messaging_product", "whatsapp",
        "to", to,
        "type", "template",
        "template", template
    );
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
    return post(body, "sendInteractiveList to=" + to) != null;
  }

  /** Media entrante ya bajada: bytes + mime type que declaró Meta. */
  public record MediaDownload(byte[] bytes, String mimeType) {}

  /**
   * Baja un media entrante (foto de un mensaje {@code image}) por Graph API: {@code GET /{api-version}/{media-id}}
   * devuelve {@code {url, mime_type}} y esa URL (efímera) se baja con el MISMO token. Devuelve
   * {@code null} si WhatsApp está deshabilitado o algo falla (nunca lanza: el caller le pide al
   * vecino que reenvíe la foto).
   */
  public MediaDownload downloadMedia(String mediaId) {
    if (!enabled) {
      log.warn("whatsapp disabled, skip downloadMedia {}", mediaId);
      return null;
    }
    if (mediaId == null || !mediaId.matches("[A-Za-z0-9_-]{1,100}")) {
      log.warn("whatsapp downloadMedia: media id invalido");
      return null;
    }
    try {
      String[] auth = authHeader();
      String meta = mediaClient.get()
          .uri("/" + apiVersion + "/" + mediaId)
          .header(auth[0], auth[1])
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(15))
          .block();
      if (meta == null) return null;
      JsonNode node = objectMapper.readTree(meta);
      String url = node.path("url").asText("");
      String mime = node.path("mime_type").asText("");
      if (url.isBlank()) {
        log.warn("whatsapp downloadMedia {}: respuesta sin url", mediaId);
        return null;
      }
      byte[] bytes = mediaClient.get()
          .uri(java.net.URI.create(url))
          .header(auth[0], auth[1])
          .retrieve()
          .bodyToMono(byte[].class)
          .timeout(Duration.ofSeconds(30))
          .block();
      if (bytes == null || bytes.length == 0) return null;
      log.info("whatsapp downloadMedia {} OK ({} bytes, {})", mediaId, bytes.length, mime);
      return new MediaDownload(bytes, mime);
    } catch (Exception ex) {
      log.warn("whatsapp downloadMedia {} failed: {}", mediaId, ex.getMessage());
      return null;
    }
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

  /** POST al endpoint de mensajes. Devuelve el wamid ({@code messages[0].id})
   * si Meta aceptó el mensaje, o {@code null} si falló o la respuesta no trajo
   * mensaje. "Aceptado" no es "entregado": la entrega real la informa Meta
   * después por el webhook, bajo {@code statuses[]} con este mismo id. */
  private String post(Map<String, Object> body, String contextDescription) {
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
        return null;
      }
      JsonNode root = objectMapper.readTree(response);
      JsonNode messages = root.path("messages");
      if (messages.isArray() && messages.size() > 0) {
        String wamid = messages.get(0).path("id").asText("");
        log.info("whatsapp {} OK id={}", contextDescription, wamid);
        // Meta siempre devuelve id; si por lo que sea viniera vacío, igual
        // reportamos "enviado" con un marcador para no romper a los callers
        // booleanos (sendText/sendTemplate: != null).
        return wamid.isBlank() ? "wamid.unknown" : wamid;
      }
      log.warn("whatsapp {} no-message in response: {}", contextDescription,
          response.length() > 300 ? response.substring(0, 300) : response);
      return null;
    } catch (Exception ex) {
      log.warn("whatsapp {} failed: {}", contextDescription, ex.getMessage());
      return null;
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
