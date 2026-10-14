package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.repository.LeadEventRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Avisa al VECINO por WhatsApp, con la plantilla Utility
 * {@code customer_order_update}, cuando pasa algo en su pedido y todavía no
 * hay conversación de WhatsApp abierta con él.
 *
 * <p>Caso real (lead #280, 2026-10-13): pedido creado desde la web con
 * teléfono; el técnico aceptó y respondió por WhatsApp, pero todo quedó en el
 * chat web y el vecino nunca se enteró. Fixy no puede iniciar una conversación
 * de WhatsApp con texto libre: hace falta plantilla aprobada. En cuanto el
 * vecino contesta, {@link WhatsAppInboundService} pasa el lead a
 * {@code channel=whatsapp} y desde ahí el texto libre entra por la ventana de
 * 24 h (este notifier deja de intervenir).
 *
 * <p>Plantilla (idioma {@code es}): "Novedad de tu pedido en Fixy: {{1}} {{2}}
 * ..." con {{1}} = nombre del proveedor y {{2}} = novedad en minúscula inicial
 * (ver las frases de {@link #acceptedNews}, {@link #providerReplyNews},
 * {@link #scheduleProposedNews}, {@link #onTheWayNews}) y un botón URL "Ver mi
 * pedido" con sufijo dinámico {@code {leadId}/{accessToken}} (configurable).
 *
 * <p>Condiciones para enviar (todas): canal de plantilla habilitado y
 * WhatsApp configurado; teléfono del lead válido; el lead NO es de canal
 * {@code whatsapp} (esos reciben texto libre); y no se mandó ya esta plantilla
 * a ese lead en los últimos 10 minutos (dedupe por evento de timeline). Los
 * leads {@code [smoke]} SÍ se notifican: el teléfono es de quien hizo la
 * prueba. Nunca lanza.
 */
@Service
public class CustomerWhatsAppNotifier {

  private static final Logger log = LoggerFactory.getLogger(CustomerWhatsAppNotifier.class);

  /** Evento de timeline cuando Meta rechaza (o no se pudo enviar) la plantilla. */
  public static final String TEMPLATE_FAILED_EVENT = "WHATSAPP_CUSTOMER_TEMPLATE_FAILED";

  static final Duration DEDUPE_WINDOW = Duration.ofMinutes(10);
  static final int MAX_NOVEDAD_LENGTH = 200;
  static final int MAX_QUOTED_REPLY_LENGTH = 140;
  private static final int MAX_PROVIDER_NAME_LENGTH = 60;
  private static final String FALLBACK_PROVIDER_NAME = "Tu proveedor";

  private final WhatsAppService whatsAppService;
  private final LeadTimelineService timelineService;
  private final LeadEventRepository leadEventRepository;
  private final boolean enabled;
  private final String templateName;
  private final String templateLang;
  private final boolean urlButton;

  /** Leads con un envío en vuelo: evita el doble envío si dos hooks (ej.
   * aceptación + mensaje) corren a la vez y el evento de dedupe aún no existe. */
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

  public CustomerWhatsAppNotifier(
      WhatsAppService whatsAppService,
      LeadTimelineService timelineService,
      LeadEventRepository leadEventRepository,
      @Value("${fixy.whatsapp.customer-template-enabled:true}") boolean enabled,
      @Value("${fixy.whatsapp.customer-template-name:customer_order_update}") String templateName,
      @Value("${fixy.whatsapp.customer-template-lang:es}") String templateLang,
      @Value("${fixy.whatsapp.customer-template-url-button:true}") boolean urlButton
  ) {
    this.whatsAppService = whatsAppService;
    this.timelineService = timelineService;
    this.leadEventRepository = leadEventRepository;
    this.enabled = enabled;
    this.templateName = templateName;
    this.templateLang = templateLang;
    this.urlButton = urlButton;
  }

  // ---- Frases de {{2}} (minúscula inicial, leen natural tras el nombre) ----

  public static String acceptedNews(String arrivalWindow) {
    String window = collapse(arrivalWindow);
    if (window.isEmpty()) {
      return "aceptó tu pedido y te confirma día y hora por acá.";
    }
    return "aceptó tu pedido y va " + window + ".";
  }

  public static String providerReplyNews(String replyText) {
    return "te respondió: «" + truncate(collapse(replyText), MAX_QUOTED_REPLY_LENGTH) + "»";
  }

  public static String scheduleProposedNews(String proposal) {
    return "propone ir " + collapse(proposal) + ". Respondé SÍ o NO por acá.";
  }

  public static String onTheWayNews() {
    return "va en camino.";
  }

  // ---- Envío ----

  /**
   * @param providerName nombre del proveedor del lead ({{1}})
   * @param novedad      frase ya armada ({{2}}); se limpia y recorta a 200
   */
  public void notify(Lead lead, String providerName, String novedad) {
    try {
      doNotify(lead, providerName, novedad);
    } catch (Exception ex) {
      log.warn("customer whatsapp notify failed for lead {}: {}",
          lead == null ? null : lead.getId(), ex.getMessage());
    }
  }

  private void doNotify(Lead lead, String providerName, String novedad) {
    if (lead == null || lead.getId() == null || !enabled || !whatsAppService.isEnabled()) {
      return;
    }
    if ("whatsapp".equals(lead.getChannel())) {
      return;
    }
    String to = validPhone(lead.getPhone());
    String cleanNovedad = truncate(collapse(novedad), MAX_NOVEDAD_LENGTH);
    if (to == null || cleanNovedad.isEmpty()) {
      return;
    }
    if (!inFlight.add(lead.getId())) {
      return;
    }
    try {
      if (templateSentRecently(lead.getId())) {
        return;
      }
      String name = collapse(providerName);
      name = name.isEmpty() ? FALLBACK_PROVIDER_NAME : truncate(name, MAX_PROVIDER_NAME_LENGTH);
      String suffix = urlButton && lead.getAccessToken() != null && !lead.getAccessToken().isBlank()
          ? lead.getId() + "/" + lead.getAccessToken()
          : null;
      String wamid = whatsAppService.sendTemplateReturningId(
          to, templateName, templateLang, List.of(name, cleanNovedad), suffix);
      if (wamid != null && !wamid.isBlank()) {
        timelineService.appendEvent(lead, LeadTimelineService.WHATSAPP_SENT, "system",
            LeadTimelineService.whatsAppSentMessage(sentDescription(to), wamid));
      } else {
        log.warn("customer whatsapp template {} not sent for lead {} to {}", templateName, lead.getId(), to);
        timelineService.appendEvent(lead, TEMPLATE_FAILED_EVENT, "system",
            "No se pudo enviar la plantilla " + templateName + " al vecino (" + to + "): "
                + truncate(cleanNovedad, 80));
      }
    } finally {
      inFlight.remove(lead.getId());
    }
  }

  private String sentDescription(String to) {
    return "Plantilla " + templateName + " enviada por WhatsApp al vecino (" + to + ")";
  }

  /** ¿Ya salió esta plantilla para este lead dentro de la ventana de dedupe? */
  private boolean templateSentRecently(Long leadId) {
    String prefix = "Plantilla " + templateName + " enviada";
    OffsetDateTime threshold = OffsetDateTime.now().minus(DEDUPE_WINDOW);
    for (LeadEvent e : leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, LeadTimelineService.WHATSAPP_SENT)) {
      if (e.getCreatedAt() != null && e.getCreatedAt().isBefore(threshold)) {
        return false; // vienen del más nuevo al más viejo
      }
      if (e.getMessage() != null && e.getMessage().startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** Teléfono normalizado (E.164 sin "+") o null si no parece un celular
   * enrutable: 10 a 15 dígitos (UY móvil = 598 + 8 dígitos = 11). */
  static String validPhone(String raw) {
    String normalized = WhatsAppService.normalize(raw);
    if (normalized == null || normalized.length() < 10 || normalized.length() > 15) {
      return null;
    }
    return normalized;
  }

  /** Meta no admite saltos de línea, tabs ni 4+ espacios seguidos en un parámetro. */
  static String collapse(String s) {
    return s == null ? "" : s.replaceAll("\\s+", " ").trim();
  }

  static String truncate(String s, int max) {
    if (s == null || s.length() <= max) return s == null ? "" : s;
    return s.substring(0, max - 1).stripTrailing() + "…";
  }
}
