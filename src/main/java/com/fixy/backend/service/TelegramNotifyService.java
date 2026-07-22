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
 *
 * También cubre el aviso de escalamiento a humano (Salto 2 del cerebro
 * agéntico, ver ARQUITECTURA_SUPERAPP.md): cuando el agente conversacional
 * pide la acción "escalate" en su JSON de turno, {@link #notifyEscalation}
 * avisa por acá. Misma idempotencia (evento "ESCALATED_TO_HUMAN" por lead) y
 * mismo guard de "[smoke]" que los avisos de oportunidad.
 */
@Service
public class TelegramNotifyService {

  private static final Logger log = LoggerFactory.getLogger(TelegramNotifyService.class);

  static final String NOTIFIED_EVENT_TYPE = "OPS_NOTIFIED_OPPORTUNITY";
  /** Público: LeadAgentService lo usa para chequear si ya escaló este lead
   * antes de postear el mensaje al cliente (mismo evento que gatea el aviso
   * a Telegram acá — una sola fuente de verdad para la idempotencia). */
  public static final String ESCALATED_EVENT_TYPE = "ESCALATED_TO_HUMAN";
  /** Aviso a ops de "cliente escribió en un lead asignado" — throttled, no una-vez-por-lead. */
  static final String CUSTOMER_MSG_NOTIFIED_EVENT_TYPE = "OPS_NOTIFIED_CUSTOMER_MSG";
  /** Aviso a ops de disputa abierta — una vez por lead. */
  static final String DISPUTE_NOTIFIED_EVENT_TYPE = "OPS_NOTIFIED_DISPUTE";
  /** Aviso a ops de trabajo asignado que lleva 48h sin cerrar — una vez por lead. */
  static final String STALE_JOB_NOTIFIED_EVENT_TYPE = "OPS_NOTIFIED_STALE_JOB";
  /** Aviso a ops de pedido listo que nadie acepta (MatchingStaleScheduler) — una vez por lead. */
  static final String STALE_MATCHING_NOTIFIED_EVENT_TYPE = "OPS_NOTIFIED_STALE_MATCHING";

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
      // @Lazy: providerSelfService solo se usa en runtime (panelUrl). Sin esto
      // hay ciclo de beans: TelegramNotify → ProviderSelf → LeadClosing →
      // TelegramNotify (LeadClosing avisa disputas por acá). Telegram es un
      // notificador hoja — cualquier servicio puede depender de él.
      @org.springframework.context.annotation.Lazy ProviderSelfService providerSelfService,
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

  /**
   * Avisa a Carlos que un lead fue escalado a humano porque el agente no pudo
   * resolverlo solo (motivo lo decide el LLM en el turno: fuera de alcance,
   * sin proveedor, cliente frustrado/reclamo, etc). Idempotente por lead
   * (evento "ESCALATED_TO_HUMAN", mismo patrón que OPS_NOTIFIED_OPPORTUNITY)
   * y respeta el guard "[smoke]". Async por la misma razón que los avisos de
   * oportunidad: nunca debe demorar la respuesta al cliente.
   */
  @Async
  public void notifyEscalation(Lead lead, String reason, String summary) {
    if (!shouldNotify(lead, ESCALATED_EVENT_TYPE)) return;
    String text = "🆘 Escalamiento #%d: %s. Motivo: %s. %s"
        .formatted(
            lead.getId(),
            humanCategory(lead.getDetectedCategory()) + " en " + safe(lead.getLocation()),
            safe(reason),
            truncate(safe(summary), 200)
        );
    send(lead, ESCALATED_EVENT_TYPE, text, "Aviso de escalamiento a humano enviado a Telegram");
  }

  /**
   * Cierre visible de disputas (Ola 2): al cliente se le promete revisión "en
   * menos de 24h", así que ops tiene que enterarse en el momento. Idempotente
   * por lead (una disputa abierta = un aviso; el modelo de datos tampoco
   * permite reabrir) y respeta el guard "[smoke]".
   */
  @Async
  public void notifyDisputeOpened(Lead lead, String customerComment) {
    if (!shouldNotify(lead, DISPUTE_NOTIFIED_EVENT_TYPE)) return;
    String text = "⚠️ Disputa abierta en lead #%d (%s en %s, cliente %s): \"%s\" — prometimos respuesta en <24h. Resolvela con PATCH /api/leads/%d/dispute-resolution."
        .formatted(
            lead.getId(),
            humanCategory(lead.getDetectedCategory()),
            safe(lead.getLocation()),
            safe(lead.getName()),
            truncate(safe(customerComment), 150),
            lead.getId()
        );
    send(lead, DISPUTE_NOTIFIED_EVENT_TYPE, text, "Aviso de disputa abierta enviado a ops");
  }

  /**
   * Parche interino mientras WhatsApp está apagado (esperando a Meta): con el
   * lead ASIGNADO el agente ya no responde (fix del lead #109), pero el
   * proveedor no se entera de que el cliente le escribió salvo que tenga el
   * panel abierto. Avisamos a Carlos por Telegram para que le pegue un toque
   * al proveedor. A diferencia de los otros avisos NO es una-vez-por-lead:
   * cada mensaje puede importar — pero con throttle de 10 minutos por lead
   * para no spamear durante un intercambio rápido.
   */
  @Async
  public void notifyCustomerMessageForProvider(Lead lead, Provider provider, String messageText) {
    if (!enabled || lead == null || lead.getId() == null) return;
    String problem = lead.getProblem();
    if (problem != null && problem.toLowerCase(java.util.Locale.ROOT).contains("[smoke]")) {
      return;
    }
    List<com.fixy.backend.model.LeadEvent> recent = leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), CUSTOMER_MSG_NOTIFIED_EVENT_TYPE);
    if (!recent.isEmpty() && recent.get(0).getCreatedAt() != null
        && recent.get(0).getCreatedAt().isAfter(java.time.OffsetDateTime.now().minusMinutes(10))) {
      return;
    }
    String providerName = provider != null && provider.getName() != null && !provider.getName().isBlank()
        ? provider.getName()
        : safe(lead.getAssignedProvider());
    String text = "💬 Cliente escribió en lead #%d (%s en %s, proveedor %s): \"%s\" — avisale que tiene mensaje en el panel."
        .formatted(
            lead.getId(),
            humanCategory(lead.getDetectedCategory()),
            safe(lead.getLocation()),
            providerName,
            truncate(safe(messageText), 150)
        );
    send(lead, CUSTOMER_MSG_NOTIFIED_EVENT_TYPE, text, "Aviso a ops de mensaje del cliente en lead asignado");
  }

  /**
   * Caso real (2026-07-16): Barométrica Nueva Era hizo el trabajo del lead
   * #105, cobró $2500 y nunca marcó "Completado" en su panel — Carlos tuvo
   * que perseguirlo y se cerró por ops. Sin cierre no hay comisión: esto es
   * el aviso a Carlos cuando un trabajo asignado lleva 48h sin cerrarse,
   * para que le dé un toque manual al proveedor. Lo dispara
   * {@link ProviderClosingReminderScheduler}. Idempotente por lead (evento
   * "OPS_NOTIFIED_STALE_JOB") y respeta el guard "[smoke]" (vía
   * {@link #shouldNotify(Lead, String)}). Async por el mismo motivo que los
   * demás avisos: nunca debe demorar el ciclo del scheduler que lo dispara.
   */
  @Async
  /**
   * Autoregistro de proveedor (sin lead asociado → sin evento de timeline;
   * el registro pasa una sola vez, no hace falta idempotencia por evento).
   */
  public void notifyProviderSelfRegistered(Provider provider) {
    if (!enabled) return;
    try {
      String text = "🆕 Proveedor autoregistrado: %s (%s) — %s en %s. Cuenta Google: %s. Aprobalo en el admin: Proveedores → Activar."
          .formatted(
              provider.getName(),
              safe(provider.getPhone()),
              safe(provider.getCategories()),
              safe(provider.getPrimaryZone()),
              safe(provider.getGoogleEmail())
          );
      post(text);
    } catch (Exception ex) {
      log.warn("telegram notify provider-registered {} failed: {}", provider.getId(), ex.getMessage());
    }
  }

  /** Pedido listo para matching que nadie aceptó tras N minutos — el cliente sigue esperando (ver MatchingStaleScheduler). */
  public void notifyStaleMatching(Lead lead, long minutes) {
    if (!shouldNotify(lead, STALE_MATCHING_NOTIFIED_EVENT_TYPE)) return;
    String text = "🕳️ Pedido #%d (%s en %s) lleva %d min sin que nadie lo acepte — el cliente ya fue avisado, mirá si hay que mover proveedores a mano."
        .formatted(
            lead.getId(),
            humanCategory(lead.getDetectedCategory()),
            safe(lead.getLocation()),
            minutes
        );
    send(lead, STALE_MATCHING_NOTIFIED_EVENT_TYPE, text, "Aviso de matching estancado enviado a ops");
  }

  public void notifyStaleJob(Lead lead, String providerName) {
    if (!shouldNotify(lead, STALE_JOB_NOTIFIED_EVENT_TYPE)) return;
    String text = "⏰ Trabajo #%d (%s en %s, proveedor %s) lleva 48h sin cerrar — dale un toque al proveedor."
        .formatted(
            lead.getId(),
            humanCategory(lead.getDetectedCategory()),
            safe(lead.getLocation()),
            safe(providerName)
        );
    send(lead, STALE_JOB_NOTIFIED_EVENT_TYPE, text, "Aviso de trabajo sin cerrar enviado a ops");
  }

  private boolean shouldNotify(Lead lead) {
    return shouldNotify(lead, NOTIFIED_EVENT_TYPE);
  }

  private boolean shouldNotify(Lead lead, String eventType) {
    if (!enabled) return false;
    if (lead == null || lead.getId() == null) return false;
    String problem = lead.getProblem();
    if (problem != null && problem.toLowerCase(java.util.Locale.ROOT).contains("[smoke]")) {
      return false;
    }
    boolean alreadyNotified = !leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), eventType)
        .isEmpty();
    return !alreadyNotified;
  }

  private void send(Lead lead, String text) {
    send(lead, NOTIFIED_EVENT_TYPE, text, "Aviso de oportunidad enviado a Telegram");
  }

  private void send(Lead lead, String eventType, String text, String timelineMessage) {
    try {
      boolean sent = post(text);
      if (sent) {
        leadTimelineService.appendEvent(lead, eventType, "system", timelineMessage);
      }
    } catch (Exception ex) {
      log.warn("telegram notify (type={}) lead={} failed: {}", eventType, lead.getId(), ex.getMessage());
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
