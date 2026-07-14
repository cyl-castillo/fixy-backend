package com.fixy.backend.service;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadEventRepository;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Parche interino del hueco #1 del lado proveedor: hasta que haya
 * credenciales de Meta para avisar directo al proveedor por WhatsApp, cuando
 * un lead queda listo para matching le avisamos a Carlos por Telegram con el
 * resumen y los links de panel de los proveedores que matchean, para que él
 * reenvíe por WhatsApp en un toque.
 *
 * Mismo patrón que {@link WhatsAppService} / {@link MercadoPagoService}:
 * constructor con {@code @Value(...:)}, "enabled" solo si hay credenciales,
 * HTTP directo sin SDK, timeout corto, catch-all que loguea y nunca rompe el
 * flujo de negocio.
 *
 * Config requerida:
 *   fixy.telegram.bot-token   (token del bot, via BotFather)
 *   fixy.telegram.chat-id     (chat_id de Carlos al que se manda el aviso)
 *
 * Idempotencia: se registra un evento de timeline "OPS_NOTIFIED_OPPORTUNITY"
 * por lead y se chequea antes de mandar, para no avisar dos veces el mismo
 * lead aunque el gatillo se dispare por más de un camino (chat-first
 * automático vía tryAutoMatch, y matching manual/legacy vía
 * LeadService.generateMatches).
 */
@Service
public class TelegramNotifyService {

  private static final Logger log = LoggerFactory.getLogger(TelegramNotifyService.class);

  static final String NOTIFIED_EVENT_TYPE = "OPS_NOTIFIED_OPPORTUNITY";

  private final WebClient client;
  private final LeadEventRepository leadEventRepository;
  private final LeadTimelineService leadTimelineService;
  private final ProviderSelfService providerSelfService;
  private final String botToken;
  private final String chatId;
  private final String publicAppBaseUrl;
  private final boolean enabled;

  public TelegramNotifyService(
      LeadEventRepository leadEventRepository,
      LeadTimelineService leadTimelineService,
      ProviderSelfService providerSelfService,
      @Value("${fixy.telegram.bot-token:}") String botToken,
      @Value("${fixy.telegram.chat-id:}") String chatId,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl,
      @Value("${fixy.telegram.base-url:https://api.telegram.org}") String baseUrl
  ) {
    this.leadEventRepository = leadEventRepository;
    this.leadTimelineService = leadTimelineService;
    this.providerSelfService = providerSelfService;
    this.botToken = botToken;
    this.chatId = chatId;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
    this.enabled = botToken != null && !botToken.isBlank()
        && chatId != null && !chatId.isBlank();
    this.client = WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    log.info("TelegramNotifyService initialized: enabled={}", enabled);
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Lead recién listo para matching y con >=1 proveedor que matchea. Manda
   * UNA vez por lead (ver idempotencia en la clase). No-op silencioso si el
   * servicio está deshabilitado, el lead ya fue notificado, o el problema
   * contiene "[smoke]" (tráfico de humo, no es una oportunidad real).
   *
   * Async: nunca debe demorar la respuesta al cliente que dispara el
   * matching (ni el turno de chat ni el endpoint público de generateMatches).
   */
  @Async
  public void notifyOpportunityWithMatches(Lead lead, List<ProviderCatalogItem> matches) {
    if (!shouldNotify(lead)) return;
    StringBuilder text = new StringBuilder();
    text.append("🔔 Oportunidad #").append(lead.getId()).append(": ")
        .append(humanCategory(lead.getDetectedCategory())).append(" en ").append(safe(lead.getLocation()))
        .append(" (urgencia ").append(safe(lead.getUrgency())).append("). ")
        .append(truncate(safe(lead.getProblem()), 100)).append(". ")
        .append("Proveedores:");
    for (ProviderCatalogItem match : matches) {
      text.append('\n').append(match.name()).append(" (tel ").append(safe(match.phone())).append(") panel: ")
          .append(panelUrl(match.id()));
    }
    text.append("\nReenviá el panel por WhatsApp.");
    send(lead, text.toString());
  }

  /**
   * Lead listo para matching pero SIN proveedores en la categoría MVP: hay
   * demanda sin oferta. Se avisa igual, con un mensaje distinto, para que
   * Carlos se entere de qué conseguir. Misma idempotencia que el caso con
   * matches (un solo evento OPS_NOTIFIED_OPPORTUNITY por lead). Async por la
   * misma razón que {@link #notifyOpportunityWithMatches}.
   */
  @Async
  public void notifyDemandWithoutSupply(Lead lead) {
    if (!shouldNotify(lead)) return;
    String text = "🔔 Oportunidad #%d: %s en %s (urgencia %s). %s. Sin proveedores para %s en %s — conseguir uno."
        .formatted(
            lead.getId(),
            humanCategory(lead.getDetectedCategory()),
            safe(lead.getLocation()),
            safe(lead.getUrgency()),
            truncate(safe(lead.getProblem()), 100),
            humanCategory(lead.getDetectedCategory()),
            safe(lead.getLocation())
        );
    send(lead, text);
  }

  private boolean shouldNotify(Lead lead) {
    if (!enabled) return false;
    if (lead == null || lead.getId() == null) return false;
    String problem = lead.getProblem();
    if (problem != null && problem.toLowerCase(java.util.Locale.ROOT).contains("[smoke]")) {
      return false;
    }
    boolean alreadyNotified = !leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), NOTIFIED_EVENT_TYPE)
        .isEmpty();
    return !alreadyNotified;
  }

  private void send(Lead lead, String text) {
    try {
      boolean sent = post(text);
      if (sent) {
        leadTimelineService.appendEvent(lead, NOTIFIED_EVENT_TYPE, "system",
            "Aviso de oportunidad enviado a Telegram");
      }
    } catch (Exception ex) {
      log.warn("telegram notifyOpportunity lead={} failed: {}", lead.getId(), ex.getMessage());
    }
  }

  private String panelUrl(Long providerId) {
    Provider provider = providerSelfService.ensureAccessToken(providerId);
    return "%s/p/%d/%s".formatted(publicAppBaseUrl, provider.getId(), provider.getAccessToken());
  }

  private boolean post(String text) {
    try {
      java.util.Map<String, Object> body = java.util.Map.of(
          "chat_id", chatId,
          "text", text
      );
      String response = client.post()
          .uri("/bot{token}/sendMessage", botToken)
          .bodyValue(body)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(10))
          .block();
      if (response == null) {
        log.warn("telegram sendMessage: empty response");
        return false;
      }
      log.info("telegram sendMessage OK");
      return true;
    } catch (Exception ex) {
      log.warn("telegram sendMessage failed: {}", ex.getMessage());
      return false;
    }
  }

  private String humanCategory(String raw) {
    return switch (raw == null ? "" : raw.toLowerCase(java.util.Locale.ROOT).trim()) {
      case "plomeria" -> "plomería";
      case "barometrica" -> "barométrica";
      case "jardineria" -> "jardinería";
      case "aires_acondicionados" -> "aire acondicionado";
      case "electricidad" -> "electricidad";
      case "cerrajeria" -> "cerrajería";
      case "reparaciones" -> "reparaciones";
      case "pasteleria" -> "pastelería";
      default -> raw == null || raw.isBlank() ? "servicio sin definir" : raw;
    };
  }

  private String truncate(String text, int maxLen) {
    if (text == null) return "";
    String trimmed = text.trim();
    if (trimmed.length() <= maxLen) return trimmed;
    return trimmed.substring(0, maxLen).trim();
  }

  private String safe(String value) {
    return value == null || value.isBlank() ? "sin definir" : value;
  }
}
