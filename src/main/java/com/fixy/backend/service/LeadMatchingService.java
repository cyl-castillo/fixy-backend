package com.fixy.backend.service;

import com.fixy.backend.domain.DomainCatalog;
import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Lead;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Core Fase 1 (paso 3/3, ver CORE_FASE1_CONTRATO.md): matching automático de
 * proveedor y contacto vía WhatsApp — todo lo que antes vivía en
 * {@code LeadAgentService} bajo {@code matchNow}/{@code retryAutoMatch}/
 * {@code reofferAfterDecline}/{@code contactTopMatch}. Refactor puro: cada
 * método es copia textual del original.
 *
 * <p><b>Por qué esto y {@link LeadAssignmentService} son dos clases
 * distintas</b> (revisado a pedido del contrato de Fase 1): son caminos
 * opuestos. {@code LeadAssignmentService.acceptForProvider} es el PROVEEDOR
 * tomando un lead desde su bandeja o WhatsApp (transición típica
 * PROVIDER_CONTACTED/NEW → ASSIGNED, con asignación atómica por escritura en
 * base para resolver la carrera entre dos proveedores). Esta clase es FIXY
 * contactando proactivamente al mejor proveedor disponible apenas el pedido
 * queda listo (transición → PROVIDER_CONTACTED, antes de que ningún humano
 * haga nada) — push, registro de oferta, mensaje al cliente y template de
 * WhatsApp. No hay solapamiento real: una decide "quién se queda con el
 * lead", la otra decide "a quién le ofrezco el lead primero".
 */
@Service
public class LeadMatchingService {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LeadMatchingService.class);

  private final ProviderCatalogService providerCatalogService;
  private final com.fixy.backend.repository.LeadRepository leadRepository;
  private final LeadMessageService leadMessageService;
  private final com.fixy.backend.repository.ProviderRepository providerRepository;
  private final com.fixy.backend.repository.ProviderOfferRepository providerOfferRepository;
  private final com.fixy.backend.repository.ServiceCatalogItemRepository serviceCatalogItemRepository;
  private final LeadTimelineService leadTimelineService;
  private final PushNotificationService pushNotificationService;
  private final WhatsAppService whatsappService;
  private final TelegramNotifyService telegramNotifyService;
  private final SearchDeadlineService searchDeadlineService;
  private final String whatsappTemplateName;
  private final String whatsappTemplateLang;
  private final String publicAppBaseUrl;

  public LeadMatchingService(
      ProviderCatalogService providerCatalogService,
      com.fixy.backend.repository.LeadRepository leadRepository,
      LeadMessageService leadMessageService,
      com.fixy.backend.repository.ProviderRepository providerRepository,
      com.fixy.backend.repository.ProviderOfferRepository providerOfferRepository,
      com.fixy.backend.repository.ServiceCatalogItemRepository serviceCatalogItemRepository,
      LeadTimelineService leadTimelineService,
      PushNotificationService pushNotificationService,
      WhatsAppService whatsappService,
      TelegramNotifyService telegramNotifyService,
      SearchDeadlineService searchDeadlineService,
      @Value("${fixy.whatsapp.template-name:provider_lead_notification}") String whatsappTemplateName,
      @Value("${fixy.whatsapp.template-lang:es}") String whatsappTemplateLang,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl
  ) {
    this.providerCatalogService = providerCatalogService;
    this.leadRepository = leadRepository;
    this.leadMessageService = leadMessageService;
    this.providerRepository = providerRepository;
    this.providerOfferRepository = providerOfferRepository;
    this.serviceCatalogItemRepository = serviceCatalogItemRepository;
    this.leadTimelineService = leadTimelineService;
    this.pushNotificationService = pushNotificationService;
    this.whatsappService = whatsappService;
    this.telegramNotifyService = telegramNotifyService;
    this.searchDeadlineService = searchDeadlineService;
    this.whatsappTemplateName = whatsappTemplateName;
    this.whatsappTemplateLang = whatsappTemplateLang;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
  }

  /** Fuente única: DomainCatalog (domain/home-services.yml). */
  private static final java.util.Set<String> MVP_CATEGORIES =
      java.util.Set.copyOf(DomainCatalog.get().mvpIds());

  /** Copia de LeadAgentService.hasMatchingRequirements: categoría MVP conocida
   * y zona cubierta. Duplicado a propósito (ver javadoc de la clase sobre por
   * qué el loop y el matching son dos responsabilidades separadas que hoy
   * comparten esta regla simple). */
  boolean hasMatchingRequirements(Lead lead) {
    String cat = lead.getDetectedCategory() == null ? "" : lead.getDetectedCategory().toLowerCase().trim();
    String loc = lead.getLocation() == null ? "" : lead.getLocation().toLowerCase().trim();
    if (cat.isBlank() || "otro".equals(cat) || !MVP_CATEGORIES.contains(cat)) return false;
    if (loc.isBlank() || "sin definir".equals(loc)
        || !DomainCatalog.get().isCovered(loc)) return false;
    return true;
  }

  /**
   * Cuando el lead recién quedó matching-ready, busca proveedores y, si hay,
   * postea un mensaje de Fixy diciendo a qué proveedor está contactando.
   * Por ahora no envía WhatsApp automático — ese paso lo hace el ops humano
   * con el link wa.me que va en la timeline.
   */
  void tryAutoMatch(Lead lead) {
    matchNow(lead);
  }

  /**
   * Igual que {@link #tryAutoMatch}, pero público y con el resultado
   * (contactó o no) — entrada usada por el pedido estructurado (Refundación
   * fase 1, contrato §3.4: "dispara el matching inmediatamente", misma ruta
   * que el intake conversacional) para poder devolver {@code matchStatus}
   * en la respuesta del endpoint sin duplicar la lógica de acá.
   */
  public boolean matchNow(Lead lead) {
    try {
      List<ProviderCatalogItem> matches = providerCatalogService.findMatchesForLead(
          lead.getId(), lead.getDetectedCategory(), lead.getLocation());
      if (matches == null || matches.isEmpty()) {
        // "Te aviso por acá" sin teléfono es una promesa vacía si el cliente
        // cierra la pestaña: este mensaje es EL lugar donde pedir el WhatsApp
        // (verificación post-deploy 2026-07-28: el flujo de pedido completo
        // saltea la respuesta conversacional y entra directo acá).
        leadMessageService.postFromAgent(lead.getId(), withContactPhoneAsk(lead,
            noProviderMessage(lead)));
        shareRecoveryLink(lead);
        safeTelegramNotifyDemandWithoutSupply(lead);
        return false;
      }
      contactTopMatch(lead, matches, MatchContext.INITIAL);
      return true;
    } catch (Exception ex) {
      log.warn("matchNow failed for lead {}: {}", lead.getId(), ex.getMessage());
      return false;
    }
  }

  /**
   * Segunda oportunidad de matching para un lead que quedó HUÉRFANO: estaba
   * listo, en ese momento no había proveedor para su categoría/zona y el
   * sistema le prometió al cliente "te aviso apenas alguien levante el
   * pedido". Nadie volvía a intentarlo nunca — {@link #tryAutoMatch} corre
   * una sola vez, en el instante en que el lead cruza a readyForMatching
   * ({@code !wasReady && nowReady}), así que un proveedor que se registra
   * DESPUÉS jamás ve la demanda que ya estaba esperando.
   *
   * Dato que lo motivó (embudo de prod, 2026-07-29): 5 pedidos reales
   * esperaban con proveedor ACTIVO ya registrado en su misma categoría y
   * zona — 3 de aires (#128/#135/#147, Carnot Clima se registró el 23/07) y
   * 2 de decoración (#150/#180, Daya Dream Deco se registró el 25/07). Sus
   * timelines tenían UN solo evento: el de creación.
   *
   * Devuelve true si consiguió proveedor y lo contactó. La elegibilidad del
   * lead la decide {@code OrphanMatchRetryScheduler}; acá solo se reintenta.
   */
  public boolean retryAutoMatch(Long leadId) {
    try {
      Lead lead = leadRepository.findById(leadId).orElse(null);
      if (lead == null) {
        return false;
      }
      // Para lead concreto: excluye a los que ya rechazaron ESTE pedido. Sin
      // este filtro el reintento reofrecía el mismo lead al mismo proveedor
      // en cada ciclo (bug real del 2026-07-29, leads #128/#135/#147).
      List<ProviderCatalogItem> matches = providerCatalogService.findMatchesForLead(
          leadId, lead.getDetectedCategory(), lead.getLocation());
      if (matches == null || matches.isEmpty()) {
        // Silencio a propósito: el cliente YA recibió el aviso honesto de
        // "por ahora no tengo proveedores libres" cuando el pedido quedó
        // listo. Repetirlo en cada ciclo del scheduler sería spam.
        return false;
      }
      contactTopMatch(lead, matches, MatchContext.SCHEDULED_RETRY);
      return true;
    } catch (Exception ex) {
      log.warn("retryAutoMatch failed for lead {}: {}", leadId, ex.getMessage());
      return false;
    }
  }

  /**
   * Re-oferta en el acto tras un NO del proveedor contactado (Refundación
   * fase 1, contrato §4 — cierra el TODO histórico de
   * {@code WhatsAppWebhookController.rejectLead}). El caller ya registró el
   * decline ANTES de llamar acá (mismo criterio que
   * {@code ProviderSelfService.releaseAfterProviderCancel}), así que
   * {@code findMatchesForLead} excluye al que acaba de rechazar. Público
   * porque el webhook vive en otro paquete. A diferencia de
   * {@link #retryAutoMatch}, acá el cliente NO recibió todavía ningún aviso
   * de "no hay más proveedores" — si no hay siguiente, hay que decirlo
   * ahora, no callar.
   */
  public boolean reofferAfterDecline(Long leadId) {
    try {
      Lead lead = leadRepository.findById(leadId).orElse(null);
      if (lead == null) {
        return false;
      }
      List<ProviderCatalogItem> matches = providerCatalogService.findMatchesForLead(
          leadId, lead.getDetectedCategory(), lead.getLocation());
      if (matches == null || matches.isEmpty()) {
        leadMessageService.postFromAgent(lead.getId(), withContactPhoneAsk(lead,
            noProviderMessage(lead)));
        shareRecoveryLink(lead);
        safeTelegramNotifyDemandWithoutSupply(lead);
        return false;
      }
      contactTopMatch(lead, matches, MatchContext.DECLINE_REOFFER);
      return true;
    } catch (Exception ex) {
      log.warn("reofferAfterDecline failed for lead {}: {}", leadId, ex.getMessage());
      return false;
    }
  }

  /**
   * Tier 2 (contrato §B.3): mensaje honesto "no hay técnico todavía", con la
   * hora límite hasta la que Fixy promete seguir buscando. Compartido por
   * {@link #matchNow} y {@link #reofferAfterDecline} cuando no hay
   * candidatos — el lead ya tiene {@code searchDeadlineAt} seteado por
   * {@link SearchDeadlineService#begin} al quedar listo para matching.
   */
  private String noProviderMessage(Lead lead) {
    String deadline = searchDeadlineService.formatHHmm(lead.getSearchDeadlineAt());
    if (deadline.isBlank()) {
      // Defensivo: no debería pasar (todo lead readyForMatching pasa por
      // begin() antes de llegar acá), pero sin deadline no se puede prometer
      // una hora — cae al copy sin hora en vez de mostrar "las ".
      return "Por ahora no tengo técnico libre en %s para %s. Sigo buscando y te aviso por acá apenas consiga."
          .formatted(lead.getLocation(), humanCategory(lead.getDetectedCategory()));
    }
    return "Por ahora no tengo técnico libre en %s para %s. Sigo buscando hasta las %s; si a esa hora no conseguí, te aviso y vemos alternativas."
        .formatted(lead.getLocation(), humanCategory(lead.getDetectedCategory()), deadline);
  }

  /**
   * Distingue por qué se está contactando a un proveedor — solo cambia el
   * copy que ve el cliente/la timeline, la lógica de contacto es idéntica
   * (ver {@link #contactTopMatch}).
   */
  private enum MatchContext {
    /** Matching del momento: el lead recién quedó listo (chat u orden). */
    INITIAL,
    /** Reintento diferido del scheduler de huérfanos ({@link #retryAutoMatch}). */
    SCHEDULED_RETRY,
    /** Re-oferta inmediata tras un NO del proveedor anterior ({@link #reofferAfterDecline}). */
    DECLINE_REOFFER
  }

  /**
   * Contacta al mejor proveedor de la lista: push, asignación, timeline,
   * aviso al cliente y template de WhatsApp. Compartido por el matching del
   * momento ({@link #matchNow}), el reintento diferido ({@link
   * #retryAutoMatch}) y la re-oferta tras rechazo ({@link
   * #reofferAfterDecline}) — {@code context} solo cambia el texto, para que
   * el cliente entienda qué está pasando en cada caso.
   */
  private void contactTopMatch(Lead lead, List<ProviderCatalogItem> matches, MatchContext context) {
    safeTelegramNotifyOpportunity(lead, matches);
    ProviderCatalogItem top = matches.get(0);
    com.fixy.backend.model.Provider providerEntity = providerRepository.findById(top.id()).orElse(null);

    // Tier 2 (contrato §A.2): registro de la oferta uno-a-uno (offeredAt=now,
    // inWindow=ventana declarada, context=por qué se contacta). lastContactedAt
    // deja de ser columna muerta.
    java.time.OffsetDateTime now = searchDeadlineService.now();
    if (providerEntity != null) {
      boolean inWindow = com.fixy.backend.model.AvailabilityWindows
          .parse(providerEntity.getAvailabilityWindows())
          .isOpenAt(now.atZoneSameInstant(java.time.ZoneId.of("America/Montevideo")));
      com.fixy.backend.model.ProviderOffer offer = new com.fixy.backend.model.ProviderOffer();
      offer.setLeadId(lead.getId());
      offer.setProviderId(providerEntity.getId());
      offer.setContext(switch (context) {
        case INITIAL -> com.fixy.backend.model.ProviderOfferContext.INITIAL;
        case SCHEDULED_RETRY -> com.fixy.backend.model.ProviderOfferContext.SCHEDULED_RETRY;
        case DECLINE_REOFFER -> com.fixy.backend.model.ProviderOfferContext.DECLINE_REOFFER;
      });
      offer.setOfferedAt(now);
      offer.setInWindow(inWindow);
      providerOfferRepository.save(offer);
      providerEntity.setLastContactedAt(now);
      providerRepository.save(providerEntity);
    }

    // Push al proveedor matcheado (si se suscribió): el camino AUTOMÁTICO
    // también avisa, no solo el manual de generateMatches. Async y no-op
    // sin claves VAPID — nunca interrumpe el matching.
    if (providerEntity != null && !HomeServicesPolicy.isSmokeLead(lead)) {
      pushNotificationService.notifyProvider(
          providerEntity.getId(),
          providerEntity.getAccessToken(),
          "Nueva oportunidad para vos",
          "%s en %s — entrá a tu panel para aceptarla".formatted(
              humanCategory(lead.getDetectedCategory()),
              safe(lead.getLocation(), "tu zona")));
    }

    // Marco el lead como "esperando respuesta del proveedor" para que el
    // webhook pueda vincular las respuestas de WhatsApp al lead correcto.
    lead.setAssignedProviderId(top.id());
    lead.setAssignedProvider(top.name());
    lead.setStatus(com.fixy.backend.model.LeadStatus.PROVIDER_CONTACTED);
    leadRepository.save(lead);
    leadTimelineService.appendEvent(lead, "PROVIDER_CONTACTED", "system",
        switch (context) {
          case SCHEDULED_RETRY -> "Reintento de matching: contactando a %s (el pedido esperaba sin proveedor)".formatted(top.name());
          case DECLINE_REOFFER -> "Re-oferta tras rechazo: contactando a %s via WhatsApp".formatted(top.name());
          case INITIAL -> "Contactando a %s via WhatsApp".formatted(top.name());
        });

    // Aviso conversacional al cliente: contactando, NO "conseguido" — todavía
    // no hay confirmación real del proveedor (ver PLAN_SUPERAPP_CLIENTE.md
    // Ola 1 #2). Si el proveedor rechaza después, el cliente no debe sentir
    // que le mintieron.
    leadMessageService.postFromAgent(lead.getId(), withContactPhoneAsk(lead,
        switch (context) {
          case SCHEDULED_RETRY -> "¡Buenas noticias! Apareció un proveedor para tu pedido: estoy contactando a %s para %s en %s. Te aviso por acá apenas confirme."
              .formatted(top.name(), humanCategory(lead.getDetectedCategory()), lead.getLocation());
          case DECLINE_REOFFER -> "El primer técnico no pudo; ya estoy contactando a otro: %s para %s en %s. Te aviso por acá apenas confirme."
              .formatted(top.name(), humanCategory(lead.getDetectedCategory()), lead.getLocation());
          case INITIAL -> "Estoy contactando a %s para %s en %s. Te aviso por acá apenas confirme."
              .formatted(top.name(), humanCategory(lead.getDetectedCategory()), lead.getLocation());
        }));
    shareRecoveryLink(lead);

    // Envio del template a WhatsApp del proveedor. Si fixy.whatsapp.* no
    // está configurado, WhatsAppService.sendTemplate retorna false y
    // queda solo el aviso al cliente (legacy manual con wa.me).
    if (providerEntity != null && whatsappService.isEnabled()) {
      String to = providerEntity.getWhatsappNumber();
      if (to == null || to.isBlank()) to = providerEntity.getPhone();
      if (to != null && !to.isBlank()) {
        boolean sent = whatsappService.sendTemplate(
            to,
            whatsappTemplateName,
            whatsappTemplateLang,
            providerTemplateParams(lead)
        );
        if (!sent) {
          log.warn("autoMatch: WhatsApp template send failed para lead {} provider {}", lead.getId(), top.id());
        }
      }
    }
  }

  /**
   * Parámetros {{1}}/{{2}}/{{3}} del template de WhatsApp al proveedor. Para
   * un pedido estructurado (contrato §4: "incluye servicio, zona, ventana y
   * precio orientativo") se pisa el {{1}} genérico (categoría) por
   * "<servicio> ($<precio>)" y el {{3}} de urgencia por la ventana horaria
   * elegida — el texto fijo del template ya termina en "¿Lo tomás? Respondé
   * SÍ o NO", así que alcanza con reusar los mismos 3 placeholders sin
   * necesitar un template nuevo aprobado por Meta. Leads orgánicos (sin
   * serviceCode) mantienen exactamente el comportamiento de siempre.
   */
  private List<String> providerTemplateParams(Lead lead) {
    String location = lead.getLocation() == null ? "" : lead.getLocation();
    if (lead.getServiceCode() != null && !lead.getServiceCode().isBlank()) {
      com.fixy.backend.model.ServiceCatalogItem service =
          serviceCatalogItemRepository.findByCode(lead.getServiceCode()).orElse(null);
      if (service != null) {
        String serviceLabel = "%s ($%d)".formatted(service.getName(), service.getPriceFrom());
        String windowLabel = com.fixy.backend.model.OrderTimeWindow.labelForId(lead.getTimeWindow());
        return List.of(serviceLabel, location, windowLabel);
      }
    }
    return List.of(
        humanCategory(lead.getDetectedCategory()),
        location,
        lead.getUrgency() == null ? "media" : lead.getUrgency()
    );
  }

  /**
   * "El ticket del pedido" (caso real lead #200, 2026-08-04): el pedido de
   * un cliente anónimo vive solo en el navegador donde lo hizo — si lo
   * pierde y no dejó WhatsApp, es irrecuperable. Tras el matching, el agente
   * le muestra SU PROPIO link de recuperación (/c/{id}/{token}) para que lo
   * guarde — funciona en cualquier dispositivo, no depende del teléfono.
   * Una sola vez por pedido (las correcciones re-disparan el matching).
   */
  private void shareRecoveryLink(Lead lead) {
    try {
      if (lead.getAccessToken() == null || lead.getAccessToken().isBlank()) {
        return;
      }
      // Solo para quien NO tiene otro canal de vuelta: si ya hay teléfono
      // (dejó WhatsApp, o entró POR WhatsApp) el link es ruido — su canal
      // de recuperación es el número.
      if (lead.getPhone() != null && !lead.getPhone().isBlank()) {
        return;
      }
      String marker = "/c/" + lead.getId() + "/";
      boolean alreadyShared = leadMessageService.recentForAgent(lead.getId(), 30).stream()
          .anyMatch(m -> !"customer".equals(m.getSender())
              && m.getText() != null && m.getText().contains(marker));
      if (alreadyShared) {
        return;
      }
      leadMessageService.postFromAgent(lead.getId(),
          "📌 Guardá este link para volver a tu pedido cuando quieras, desde cualquier celular o computadora: %s/c/%d/%s"
              .formatted(publicAppBaseUrl, lead.getId(), lead.getAccessToken()));
    } catch (Exception ex) {
      log.warn("shareRecoveryLink failed for lead {}: {}", lead.getId(), ex.getMessage());
    }
  }

  /**
   * Anexa el pedido de WhatsApp a un mensaje del agente si corresponde (sin
   * teléfono en el lead, sin haberlo pedido antes y sin que el mensaje ya lo
   * pida). Copia de {@code LeadAgentService.withContactPhoneAsk} — el loop
   * conversacional tiene su propia copia porque la usa en un camino
   * (respuesta directa del turno) que no pasa por acá.
   */
  private String withContactPhoneAsk(Lead lead, String message) {
    boolean phoneMissing = lead.getPhone() == null || lead.getPhone().isBlank();
    if (phoneMissing && !HomeServicesPolicy.asksForContactPhone(message) && !contactPhoneAlreadyAsked(lead.getId())) {
      return message + " " + LeadAgentService.CONTACT_PHONE_ASK;
    }
    return message;
  }

  /** true si el agente ya pidió el WhatsApp en algún mensaje anterior — se
   * pide UNA vez, no se insiste. Copia de {@code LeadAgentService.contactPhoneAlreadyAsked}. */
  private boolean contactPhoneAlreadyAsked(Long leadId) {
    try {
      return leadMessageService.recentForAgent(leadId, 30).stream()
          .anyMatch(m -> !"customer".equals(m.getSender())
              && m.getText() != null
              && HomeServicesPolicy.asksForContactPhone(m.getText()));
    } catch (Exception ex) {
      // Ante la duda no repreguntar: molesta más pedir dos veces que no pedir.
      return true;
    }
  }

  /** Nunca debe interrumpir el matching: TelegramNotifyService ya se protege
   *  internamente, pero esto es una segunda red de seguridad barata. */
  private void safeTelegramNotifyOpportunity(Lead lead, List<ProviderCatalogItem> matches) {
    try {
      telegramNotifyService.notifyOpportunityWithMatches(lead, matches);
    } catch (Exception ex) {
      log.warn("telegram notifyOpportunity failed for lead {}: {}", lead.getId(), ex.getMessage());
    }
  }

  private void safeTelegramNotifyDemandWithoutSupply(Lead lead) {
    try {
      telegramNotifyService.notifyDemandWithoutSupply(lead);
    } catch (Exception ex) {
      log.warn("telegram notifyDemandWithoutSupply failed for lead {}: {}", lead.getId(), ex.getMessage());
    }
  }

  /** Deriva del catálogo único DomainCatalog (domain/home-services.yml). Copia de
   * {@code LeadAgentService.humanCategory}. */
  private String humanCategory(String raw) {
    return DomainCatalog.get().humanLabel(raw);
  }

  /** Copia de {@code LeadAgentService.safe}. */
  private String safe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
