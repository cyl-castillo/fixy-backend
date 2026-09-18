package com.fixy.backend.service;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderLeadDecline;
import com.fixy.backend.model.ProviderOffer;
import com.fixy.backend.model.ProviderOfferResponse;
import com.fixy.backend.model.ServiceCategory;
import com.fixy.backend.model.SmokeTraffic;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderOfferRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Un solo scheduler de matching (Refundación de Fixy, fase 2, contrato
 * REFUNDACION_FASE2_CONTRATO.md §C): reemplaza a {@code
 * MatchingStaleScheduler}, {@code MatchingAutoReleaseScheduler} y {@code
 * OrphanMatchRetryScheduler} (borrados). Una sola pasada {@link
 * #processOnce()} cada {@code fixy.matching.watchdog.scheduler-fixed-delay-ms}
 * (default 10 min), kill-switch {@code fixy.matching.watchdog.enabled}.
 *
 * <p>Tres frentes, en orden:
 * <ol>
 *   <li><b>Contactado sin respuesta</b> (status {@code PROVIDER_CONTACTED},
 *   medido desde el último evento {@code PROVIDER_CONTACTED}): a los {@code
 *   stale-minutes} (45, o 20 si el lead tiene plan Casa a distancia) avisa
 *   UNA vez (cliente + proveedor + ops); a las {@code release-hours} (12, o
 *   4 con plan) registra el decline implícito del proveedor contactado y
 *   re-ofrece EN EL ACTO al siguiente candidato ({@link
 *   LeadAgentService#reofferAfterDecline}) — si no hay siguiente, mismo
 *   "pozo abierto" que el viejo {@code MatchingAutoReleaseScheduler} (reusa
 *   {@link ProviderSelfService#releaseAfterTimeout}: status vuelve a NEW sin
 *   asignar, evento {@code AUTO_RELEASED}, mensaje al cliente, push) + push a
 *   hasta 5 candidatos.</li>
 *   <li><b>Huérfanos</b> ({@code NEW}/{@code IN_REVIEW} con categoría y
 *   zona, sin asignado, edad ≤ {@code orphan-max-age-days} 14, sin {@code
 *   PROVIDER_CONTACTED} en los últimos {@code orphan-retry-minutes} 60):
 *   {@link LeadAgentService#matchNow} la primera vez (mensaje honesto si no
 *   hay candidatos, evento propio {@code MATCH_BLOCKED} para no repetirlo);
 *   con {@code MATCH_BLOCKED} ya registrado, reintentos silenciosos vía
 *   {@link LeadAgentService#retryAutoMatch} — mismo criterio de "no
 *   repetir el mensaje al cliente" que pedía el contrato, sin dejar de
 *   reintentar cuando un proveedor nuevo se registra.</li>
 *   <li>Resumen Telegram por corrida (leads liberados al pozo abierto)
 *   solo si hubo alguno — igual que el viejo {@code MatchingAutoReleaseScheduler}.</li>
 * </ol>
 */
@Service
public class MatchingWatchdogScheduler {

  private static final Logger log = LoggerFactory.getLogger(MatchingWatchdogScheduler.class);

  static final String STALE_EVENT_TYPE = "MATCHING_STALE_NOTIFIED";
  /** Compuerta del plan de 90 días: el técnico contesta en 15 min. Solo ops se entera acá; el cliente recién a los stale-minutes. */
  static final String SLOW_EVENT_TYPE = "PROVIDER_SLOW_NOTIFIED";
  static final long SLOW_MINUTES = 15;
  static final String MATCH_BLOCKED_EVENT_TYPE = "MATCH_BLOCKED";
  private static final String PROVIDER_CONTACTED_EVENT_TYPE = "PROVIDER_CONTACTED";
  private static final Set<LeadStatus> ORPHAN_WAITING_STATUSES = Set.of(LeadStatus.NEW, LeadStatus.IN_REVIEW);
  private static final int MAX_PROVIDER_PUSHES = 5;
  private static final int MAX_ORPHANS_PER_RUN = 10;
  private static final int MAX_DEADLINE_PER_RUN = 20;
  private static final int MAX_MUTE_PER_RUN = 20;

  static final String CUSTOMER_STALE_MESSAGE =
      "Tu pedido está demorando más de lo normal en conseguir proveedor. Lo seguimos moviendo y ya "
          + "avisamos a una persona de Fixy para que lo mire — te escribimos apenas haya novedades.";

  /** Tier 2 (contrato §B.3): frente "deadline" — se agotó la hora límite de
   * búsqueda sin técnico asignado. Gatea la eligibilidad Y el aviso de
   * Telegram (idempotente, una sola vez por deadline vigente). */
  static final String SEARCH_DEADLINE_MISSED_EVENT_TYPE = "SEARCH_DEADLINE_MISSED";
  /** Tier 2 (contrato §B.3): frente "pedido mudo" — >=4h sin un solo mensaje
   * visible para el vecino en un lead real todavía en curso. Solo ops. */
  static final String MUTE_LEAD_NOTIFIED_EVENT_TYPE = "MUTE_LEAD_NOTIFIED";
  private static final long MUTE_HOURS = 4;
  private static final Set<LeadStatus> MUTE_CANDIDATE_STATUSES =
      Set.of(LeadStatus.NEW, LeadStatus.IN_REVIEW, LeadStatus.PROVIDER_CONTACTED);
  private static final Set<String> VISIBLE_TO_CUSTOMER_AUDIENCES = Set.of("all", "customer_only");

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final LeadMessageRepository leadMessageRepository;
  private final ProviderRepository providerRepository;
  private final ProviderLeadDeclineRepository declineRepository;
  private final ProviderOfferRepository providerOfferRepository;
  private final ProviderCatalogService providerCatalogService;
  private final ProviderSelfService providerSelfService;
  private final LeadAgentService leadAgentService;
  private final LeadTimelineService timelineService;
  private final LeadMessageService messageService;
  private final PushNotificationService pushNotificationService;
  private final TelegramNotifyService telegramNotifyService;
  private final SearchDeadlineService searchDeadlineService;

  private final boolean enabled;
  private final long staleMinutes;
  private final long staleMinutesRemoteCare;
  private final long releaseHours;
  private final long releaseHoursRemoteCare;
  private final long orphanMaxAgeDays;
  private final long orphanRetryMinutes;
  private final Clock clock;

  public MatchingWatchdogScheduler(
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      LeadMessageRepository leadMessageRepository,
      ProviderRepository providerRepository,
      ProviderLeadDeclineRepository declineRepository,
      ProviderOfferRepository providerOfferRepository,
      ProviderCatalogService providerCatalogService,
      ProviderSelfService providerSelfService,
      LeadAgentService leadAgentService,
      LeadTimelineService timelineService,
      LeadMessageService messageService,
      PushNotificationService pushNotificationService,
      TelegramNotifyService telegramNotifyService,
      SearchDeadlineService searchDeadlineService,
      @Value("${fixy.matching.watchdog.enabled:true}") boolean enabled,
      @Value("${fixy.matching.watchdog.stale-minutes:45}") long staleMinutes,
      @Value("${fixy.matching.watchdog.stale-minutes-remote-care:20}") long staleMinutesRemoteCare,
      @Value("${fixy.matching.watchdog.release-hours:12}") long releaseHours,
      @Value("${fixy.matching.watchdog.release-hours-remote-care:4}") long releaseHoursRemoteCare,
      @Value("${fixy.matching.watchdog.orphan-max-age-days:14}") long orphanMaxAgeDays,
      @Value("${fixy.matching.watchdog.orphan-retry-minutes:60}") long orphanRetryMinutes,
      Clock clock
  ) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.leadMessageRepository = leadMessageRepository;
    this.providerRepository = providerRepository;
    this.declineRepository = declineRepository;
    this.providerOfferRepository = providerOfferRepository;
    this.providerCatalogService = providerCatalogService;
    this.providerSelfService = providerSelfService;
    this.leadAgentService = leadAgentService;
    this.timelineService = timelineService;
    this.messageService = messageService;
    this.pushNotificationService = pushNotificationService;
    this.telegramNotifyService = telegramNotifyService;
    this.searchDeadlineService = searchDeadlineService;
    this.enabled = enabled;
    this.staleMinutes = staleMinutes;
    this.staleMinutesRemoteCare = staleMinutesRemoteCare;
    this.releaseHours = releaseHours;
    this.releaseHoursRemoteCare = releaseHoursRemoteCare;
    this.orphanMaxAgeDays = orphanMaxAgeDays;
    this.orphanRetryMinutes = orphanRetryMinutes;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.matching.watchdog.scheduler-fixed-delay-ms:600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int actions = processOnce();
    if (actions > 0) {
      log.info("matching watchdog: {} acción(es) en esta corrida", actions);
    }
  }

  /** Un ciclo del job, invocable desde tests. Devuelve cuántas acciones tomó. */
  public int processOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    int actions = processContactedWithoutResponse(now);
    actions += processOrphans(now);
    actions += processSearchDeadlines(now);
    actions += processMuteLeads(now);
    return actions;
  }

  // --- 1. Contactado sin respuesta ----------------------------------------

  private int processContactedWithoutResponse(OffsetDateTime now) {
    List<Lead> candidates = leadRepository.findByStatusOrderByCreatedAtDesc(LeadStatus.PROVIDER_CONTACTED);
    List<Lead> releasedToPool = new ArrayList<>();
    int actions = 0;

    for (Lead lead : candidates) {
      if (!isContactedEligible(lead)) {
        continue;
      }
      OffsetDateTime lastContactedAt = lastEventAt(lead.getId(), PROVIDER_CONTACTED_EVENT_TYPE);
      if (lastContactedAt == null) {
        continue;
      }
      boolean withPlan = lead.getRemoteCarePlanId() != null;
      long staleThreshold = withPlan ? staleMinutesRemoteCare : staleMinutes;
      long releaseThreshold = withPlan ? releaseHoursRemoteCare : releaseHours;
      Duration sinceContact = Duration.between(lastContactedAt, now);

      if (sinceContact.toHours() >= releaseThreshold) {
        if (handleRelease(lead, releaseThreshold, releasedToPool, now)) {
          actions++;
        }
      } else {
        if (sinceContact.toMinutes() >= staleThreshold
            && !timelineService.hasEvent(lead.getId(), STALE_EVENT_TYPE)) {
          handleStale(lead, staleThreshold);
          actions++;
        }
        // Tier 2 (contrato §A.3): el aviso de "proveedor lento" a ops no
        // dispara para ofertas fuera de la ventana declarada — no cuenta
        // como NO, así que tampoco es "lento" en ese sentido. El aviso de
        // stale al cliente (arriba) sigue igual: al vecino no le cambia
        // quién tiene la culpa.
        if (sinceContact.toMinutes() >= SLOW_MINUTES
            && !timelineService.hasEvent(lead.getId(), SLOW_EVENT_TYPE)
            && isOpenOfferInWindow(lead)) {
          handleSlow(lead, sinceContact.toMinutes());
          actions++;
        }
      }
    }

    if (!releasedToPool.isEmpty()) {
      telegramNotifyService.notifyAutoReleaseSummary(releasedToPool, releaseHours);
    }
    return actions;
  }

  private boolean isContactedEligible(Lead lead) {
    if (lead.getId() == null || lead.isDisputed()) {
      return false;
    }
    if (SmokeTraffic.marks(lead.getProblem())) {
      return false;
    }
    return lead.getAssignedProviderId() != null && lead.getStatus() == LeadStatus.PROVIDER_CONTACTED;
  }

  private void handleStale(Lead lead, long thresholdMinutes) {
    try {
      messageService.postFromOps(lead.getId(), "fixy", CUSTOMER_STALE_MESSAGE);
    } catch (Exception ex) {
      log.warn("watchdog: no se pudo avisar al cliente del lead {}: {}", lead.getId(), ex.getMessage());
    }
    try {
      remindContactedProvider(lead);
    } catch (Exception ex) {
      log.warn("watchdog: push al proveedor contactado del lead {} falló: {}", lead.getId(), ex.getMessage());
    }
    telegramNotifyService.notifyStaleMatching(lead, thresholdMinutes);
    timelineService.appendEvent(lead, STALE_EVENT_TYPE, "system",
        "Sin respuesta tras %d min: se avisó a cliente, proveedor y ops".formatted(thresholdMinutes));
  }

  private void handleSlow(Lead lead, long minutes) {
    Provider contacted = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
    String name = contacted != null ? contacted.getName() : "el técnico contactado";
    telegramNotifyService.notifyProviderSlow(lead, name, minutes);
    timelineService.appendEvent(lead, SLOW_EVENT_TYPE, "system",
        "%s sin contestar tras %d min: se avisó a ops".formatted(name, minutes));
  }

  /** Tier 2 (contrato §A.3): ¿la oferta abierta de este lead con su
   * proveedor contactado nació dentro de la ventana declarada? true por
   * default si no hay oferta registrada (leads viejos previos a esta
   * feature) — no penalizar por falta de dato. */
  private boolean isOpenOfferInWindow(Lead lead) {
    if (lead.getAssignedProviderId() == null) {
      return true;
    }
    return providerOfferRepository
        .findFirstByLeadIdAndProviderIdAndRespondedAtIsNullOrderByOfferedAtDesc(
            lead.getId(), lead.getAssignedProviderId())
        .map(ProviderOffer::isInWindow)
        .orElse(true);
  }

  private void remindContactedProvider(Lead lead) {
    Provider contacted = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
    if (contacted == null) {
      return;
    }
    pushNotificationService.notifyProvider(contacted.getId(), contacted.getAccessToken(),
        "¿Podés tomar este trabajo?",
        "Sigue disponible: %s en %s — entrá a tu panel y decidí con un toque."
            .formatted(ServiceCategory.humanLabel(lead.getDetectedCategory()), safe(lead.getLocation())));
  }

  /**
   * @return true si esta corrida tomó una acción sobre el lead (decline +
   *         re-oferta exitosa, o liberación al pozo abierto).
   */
  private boolean handleRelease(Lead lead, long thresholdHours, List<Lead> releasedToPool, OffsetDateTime now) {
    Provider unresponsive = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
    if (unresponsive == null) {
      log.warn("watchdog: lead {} con assignedProviderId {} sin provider en base, se omite",
          lead.getId(), lead.getAssignedProviderId());
      return false;
    }

    // Contrato §C.1.1.b: decline implícito del contactado ANTES de mirar
    // alternativas — findMatchesForLead ya lo excluye después de esto.
    registerImplicitDecline(lead.getId(), unresponsive.getId());
    // Tier 2 (contrato §A.2): la oferta abierta de este par se cierra TIMEOUT.
    closeOpenOfferAsTimeout(lead.getId(), unresponsive.getId(), now);

    List<ProviderCatalogItem> alternatives;
    try {
      alternatives = providerCatalogService.findMatchesForLead(
          lead.getId(), lead.getDetectedCategory(), lead.getLocation());
    } catch (Exception ex) {
      log.warn("watchdog: no se pudo buscar alternativa para el lead {}: {}", lead.getId(), ex.getMessage());
      alternatives = List.of();
    }

    if (!alternatives.isEmpty()) {
      // Re-oferta en el acto: reusa la misma ruta que el fase 1 (contrato
      // §4, WhatsAppWebhookController NO) — vuelve a buscar internamente
      // (misma lista, ya sin el que acaba de rechazar) y contacta al top.
      try {
        leadAgentService.reofferAfterDecline(lead.getId());
        return true;
      } catch (Exception ex) {
        log.warn("watchdog: re-oferta tras release falló para el lead {}: {}", lead.getId(), ex.getMessage());
        return false;
      }
    }

    // Sin alternativa: "pozo abierto" — mismo mecanismo que el viejo
    // MatchingAutoReleaseScheduler (evento AUTO_RELEASED preservado).
    try {
      providerSelfService.releaseAfterTimeout(lead, unresponsive);
    } catch (Exception ex) {
      log.warn("watchdog: no se pudo liberar el lead {}: {}", lead.getId(), ex.getMessage());
      return false;
    }
    try {
      remindCandidates(lead);
    } catch (Exception ex) {
      log.warn("watchdog: push a candidatos del lead {} falló: {}", lead.getId(), ex.getMessage());
    }
    releasedToPool.add(lead);
    return true;
  }

  private void registerImplicitDecline(Long leadId, Long providerId) {
    if (!declineRepository.existsByLeadIdAndProviderId(leadId, providerId)) {
      ProviderLeadDecline decline = new ProviderLeadDecline();
      decline.setLeadId(leadId);
      decline.setProviderId(providerId);
      declineRepository.save(decline);
    }
  }

  /** Tier 2 (contrato §A.2): cierra como TIMEOUT la oferta abierta del par
   * (lead, proveedor) — el proveedor contactado nunca respondió. No-op si
   * no hay oferta registrada (leads viejos, o ya cerrada por otra vía). */
  private void closeOpenOfferAsTimeout(Long leadId, Long providerId, OffsetDateTime now) {
    providerOfferRepository
        .findFirstByLeadIdAndProviderIdAndRespondedAtIsNullOrderByOfferedAtDesc(leadId, providerId)
        .ifPresent(offer -> {
          offer.setRespondedAt(now);
          offer.setResponse(ProviderOfferResponse.TIMEOUT);
          providerOfferRepository.save(offer);
        });
  }

  private void remindCandidates(Lead lead) {
    String title = "Volvió a estar disponible";
    String body = "%s en %s sigue buscando proveedor — entrá a tu panel y decidí con un toque."
        .formatted(ServiceCategory.humanLabel(lead.getDetectedCategory()), safe(lead.getLocation()));

    Set<Long> overdueProviderIds = providerCatalogService.overdueProviderIds();
    providerRepository.findAll().stream()
        .filter(p -> providerCatalogService.canReceiveNewWork(p, overdueProviderIds))
        .filter(p -> providerCatalogService.matchesProvider(p, lead.getDetectedCategory(), lead.getLocation()))
        .sorted(Comparator.comparing(Provider::getId))
        .limit(MAX_PROVIDER_PUSHES)
        .forEach(p -> pushNotificationService.notifyProvider(p.getId(), p.getAccessToken(), title, body));
  }

  // --- 2. Huérfanos --------------------------------------------------------

  private int processOrphans(OffsetDateTime now) {
    OffsetDateTime oldestAllowed = now.minusDays(orphanMaxAgeDays);
    OffsetDateTime recentContactCutoff = now.minusMinutes(orphanRetryMinutes);

    List<Lead> candidates = new ArrayList<>();
    for (LeadStatus status : ORPHAN_WAITING_STATUSES) {
      candidates.addAll(leadRepository.findByStatusOrderByCreatedAtDesc(status));
    }

    int actions = 0;
    int processedThisRun = 0;
    for (Lead lead : candidates) {
      if (processedThisRun >= MAX_ORPHANS_PER_RUN) {
        break;
      }
      if (!isOrphanEligible(lead, oldestAllowed, recentContactCutoff)) {
        continue;
      }
      processedThisRun++;

      // Contrato §C.1.2: SIEMPRE vía retryAutoMatch (no matchNow) para el
      // intento en sí — es el copy de "reintento" ("¡Buenas noticias!
      // Apareció un proveedor...") el que corresponde acá, primera vez o
      // no: desde la perspectiva del cliente, todo contacto que sale de
      // este watchdog (y no del momento síncrono de crear el pedido) es un
      // reintento. matchNow (copy "Estoy contactando...") solo se dispara
      // como fallback, una única vez, para el efecto lateral de "no hay
      // candidatos" (mensaje honesto + Telegram) — retryAutoMatch ya
      // confirmó con la misma data que no hay a quién ofrecerlo, así que
      // matchNow repite exactamente esa misma conclusión sin duplicar la
      // lógica de matching (mismo método público que usa OrderService).
      boolean alreadyBlocked = timelineService.hasEvent(lead.getId(), MATCH_BLOCKED_EVENT_TYPE);
      boolean contacted = leadAgentService.retryAutoMatch(lead.getId());
      if (contacted) {
        actions++;
      } else if (!alreadyBlocked) {
        leadAgentService.matchNow(lead);
        timelineService.appendEvent(lead, MATCH_BLOCKED_EVENT_TYPE, "system",
            "Sin proveedores disponibles en %s para %s".formatted(
                safe(lead.getLocation()), ServiceCategory.humanLabel(lead.getDetectedCategory())));
        actions++;
      }
    }
    return actions;
  }

  private boolean isOrphanEligible(Lead lead, OffsetDateTime oldestAllowed, OffsetDateTime recentContactCutoff) {
    if (lead.getId() == null || lead.isDisputed()) {
      return false;
    }
    if (SmokeTraffic.marks(lead.getProblem())) {
      return false;
    }
    if (!lead.isReadyForMatching()) {
      return false;
    }
    if (lead.getAssignedProviderId() != null) {
      return false;
    }
    if (lead.getCreatedAt() == null || lead.getCreatedAt().isBefore(oldestAllowed)) {
      return false;
    }
    OffsetDateTime lastContactedAt = lastEventAt(lead.getId(), PROVIDER_CONTACTED_EVENT_TYPE);
    if (lastContactedAt == null) {
      return true;
    }
    // "Que un proveedor no pueda NO mata el pedido del cliente" (fase 1,
    // ProviderSelfService#releaseAfterProviderCancel): un lead en NEW/IN_REVIEW
    // con un PROVIDER_CONTACTED en su historia SOLO llegó ahí porque se
    // liberó después (release activo o por timeout — son las dos únicas
    // rutas de vuelta a NEW). Si esa liberación es POSTERIOR al contacto,
    // el pedido vuelve a ser huérfano de una, sin esperar la ventana de
    // orphan-retry-minutes (mismo criterio que el viejo
    // OrphanMatchRetryScheduler#hasMatchingStarted). Esa ventana solo
    // aplica al caso residual sin liberación registrada.
    OffsetDateTime lastReleasedAt = lastEventAt(lead.getId(), ProviderSelfService.PROVIDER_RELEASED_EVENT_TYPE);
    OffsetDateTime lastAutoReleasedAt = lastEventAt(lead.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE);
    OffsetDateTime latestRelease = maxOf(lastReleasedAt, lastAutoReleasedAt);
    if (latestRelease != null && !latestRelease.isBefore(lastContactedAt)) {
      return true;
    }
    return lastContactedAt.isBefore(recentContactCutoff);
  }

  private OffsetDateTime maxOf(OffsetDateTime a, OffsetDateTime b) {
    if (a == null) return b;
    if (b == null) return a;
    return a.isAfter(b) ? a : b;
  }

  // --- 3. Hora límite de búsqueda (contrato §B.3) --------------------------

  private int processSearchDeadlines(OffsetDateTime now) {
    // Por estado (NEW/IN_REVIEW/PROVIDER_CONTACTED), no por assignedProviderId:
    // en PROVIDER_CONTACTED el id del técnico ya está seteado aunque no haya
    // aceptado — y ese (el que no contesta) es el caso que más importa.
    List<Lead> candidates = leadRepository
        .findBySearchDeadlineAtIsNotNullAndDisputedFalseAndStatusIn(MUTE_CANDIDATE_STATUSES);
    int actions = 0;
    int processed = 0;
    for (Lead lead : candidates) {
      if (processed >= MAX_DEADLINE_PER_RUN) {
        break;
      }
      if (!isDeadlineEligible(lead, now)) {
        continue;
      }
      processed++;
      handleSearchDeadlineMissed(lead, now);
      actions++;
    }
    return actions;
  }

  private boolean isDeadlineEligible(Lead lead, OffsetDateTime now) {
    if (lead.getId() == null) {
      return false;
    }
    if (SmokeTraffic.marks(lead.getProblem())) {
      return false;
    }
    if (lead.getSearchDeadlineAt() == null || lead.getSearchDeadlineAt().isAfter(now)) {
      return false;
    }
    // "Sin evento SEARCH_DEADLINE_MISSED posterior al deadline vigente": si
    // ya se avisó para ESTE deadline (el evento nació después de que se
    // seteó), no se repite. Si el deadline se reinició después (franja
    // cambiada, vuelta al pozo), el evento viejo queda ANTES del nuevo
    // deadline y vuelve a ser elegible.
    OffsetDateTime lastMissed = lastEventAt(lead.getId(), SEARCH_DEADLINE_MISSED_EVENT_TYPE);
    return lastMissed == null || lastMissed.isBefore(lead.getSearchDeadlineAt());
  }

  private void handleSearchDeadlineMissed(Lead lead, OffsetDateTime now) {
    // Nunca "0 horas": si el reloj dice menos de una (deadline corto en dev,
    // o el watchdog corrió justo al vencer), se redondea a 1.
    long hoursWaiting = lead.getCreatedAt() == null ? 1 : Math.max(1, Duration.between(lead.getCreatedAt(), now).toHours());
    String requestedWindow = com.fixy.backend.model.OrderTimeWindow.labelForId(lead.getTimeWindow());
    String what = requestedWindow.isBlank()
        ? "%s en %s".formatted(ServiceCategory.humanLabel(lead.getDetectedCategory()), safe(lead.getLocation()))
        : requestedWindow;
    String message = ("Pasaron %d horas y no conseguimos técnico para %s. Podés: **dejarlo abierto** (te aviso "
        + "apenas uno confirme), **cambiarlo a otro momento** desde el pedido, o **hablar con una persona** de Fixy.")
        .formatted(hoursWaiting, what);
    try {
      messageService.postFromOps(lead.getId(), "fixy", message);
    } catch (Exception ex) {
      log.warn("watchdog: no se pudo avisar deadline vencido al cliente del lead {}: {}", lead.getId(), ex.getMessage());
    }
    timelineService.appendEvent(lead, SEARCH_DEADLINE_MISSED_EVENT_TYPE, "system",
        "Hora límite de búsqueda vencida (%s) sin técnico asignado".formatted(
            searchDeadlineService.formatHHmm(lead.getSearchDeadlineAt())));
    try {
      pushNotificationService.notifyLeadHasNews(lead.getId(), "Seguimos buscando técnico", message);
    } catch (Exception ex) {
      log.warn("watchdog: push de deadline vencido falló para el lead {}: {}", lead.getId(), ex.getMessage());
    }
    try {
      telegramNotifyService.notifySearchDeadlineMissed(lead);
    } catch (Exception ex) {
      log.warn("watchdog: aviso a ops de deadline vencido falló para el lead {}: {}", lead.getId(), ex.getMessage());
    }
  }

  // --- 4. Pedido mudo (contrato §B.3) ---------------------------------------

  private int processMuteLeads(OffsetDateTime now) {
    OffsetDateTime cutoff = now.minusHours(MUTE_HOURS);
    List<Lead> candidates = new ArrayList<>();
    for (LeadStatus status : MUTE_CANDIDATE_STATUSES) {
      candidates.addAll(leadRepository.findByStatusOrderByCreatedAtDesc(status));
    }
    // Más nuevo primero entre TODOS los estados: el tope por corrida no puede
    // dejar afuera al pedido de hoy porque haya viejos de otro estado.
    candidates.sort(Comparator.comparing(Lead::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
    int actions = 0;
    int processed = 0;
    for (Lead lead : candidates) {
      if (processed >= MAX_MUTE_PER_RUN) {
        break;
      }
      OffsetDateTime lastVisible = lastVisibleMessageAt(lead);
      if (!isMuteEligible(lead, lastVisible, cutoff)) {
        continue;
      }
      processed++;
      long hoursSilent = Math.max(0, Duration.between(lastVisible, now).toHours());
      try {
        telegramNotifyService.notifyMuteLead(lead, hoursSilent);
      } catch (Exception ex) {
        log.warn("watchdog: aviso de pedido mudo falló para el lead {}: {}", lead.getId(), ex.getMessage());
      }
      // El evento lo escribe el watchdog (igual que PROVIDER_SLOW_NOTIFIED):
      // una sola vez por lead, aunque Telegram esté apagado o falle.
      timelineService.appendEvent(lead, MUTE_LEAD_NOTIFIED_EVENT_TYPE, "system",
          "%d h sin mensaje visible para el vecino: se avisó a ops".formatted(hoursSilent));
      actions++;
    }
    return actions;
  }

  private boolean isMuteEligible(Lead lead, OffsetDateTime lastVisible, OffsetDateTime cutoff) {
    if (lead.getId() == null || lead.isDisputed()) {
      return false;
    }
    if (SmokeTraffic.marks(lead.getProblem())) {
      return false;
    }
    if (!lead.isReadyForMatching()) {
      return false;
    }
    if (lastVisible == null || !lastVisible.isBefore(cutoff)) {
      return false;
    }
    return !timelineService.hasEvent(lead.getId(), MUTE_LEAD_NOTIFIED_EVENT_TYPE);
  }

  /** Último mensaje con audiencia visible para el vecino (all/customer_only)
   * de alguien que no sea el propio cliente — "de qué se dio cuenta (o no)
   * el vecino último", no el último mensaje del hilo sea de quien sea. */
  private OffsetDateTime lastVisibleMessageAt(Lead lead) {
    if (lead.getId() == null) {
      return null;
    }
    return leadMessageRepository.findByLeadIdOrderByCreatedAtDesc(lead.getId()).stream()
        .filter(m -> VISIBLE_TO_CUSTOMER_AUDIENCES.contains(m.getAudience()) && !"customer".equals(m.getSender()))
        .map(LeadMessage::getCreatedAt)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  // --- Compartido ------------------------------------------------------------

  /** Último evento del tipo dado para el lead, o null si no hay ninguno. */
  private OffsetDateTime lastEventAt(Long leadId, String type) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, type).stream()
        .map(LeadEvent::getCreatedAt)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private String safe(String value) {
    return value == null || value.isBlank() ? "sin definir" : value;
  }
}
