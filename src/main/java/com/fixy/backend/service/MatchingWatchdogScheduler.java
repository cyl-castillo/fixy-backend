package com.fixy.backend.service;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderLeadDecline;
import com.fixy.backend.model.ServiceCategory;
import com.fixy.backend.model.SmokeTraffic;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
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
  static final String MATCH_BLOCKED_EVENT_TYPE = "MATCH_BLOCKED";
  private static final String PROVIDER_CONTACTED_EVENT_TYPE = "PROVIDER_CONTACTED";
  private static final Set<LeadStatus> ORPHAN_WAITING_STATUSES = Set.of(LeadStatus.NEW, LeadStatus.IN_REVIEW);
  private static final int MAX_PROVIDER_PUSHES = 5;
  private static final int MAX_ORPHANS_PER_RUN = 10;

  static final String CUSTOMER_STALE_MESSAGE =
      "Tu pedido está demorando más de lo normal en conseguir proveedor. Lo seguimos moviendo y ya "
          + "avisamos a una persona de Fixy para que lo mire — te escribimos apenas haya novedades.";

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final ProviderRepository providerRepository;
  private final ProviderLeadDeclineRepository declineRepository;
  private final ProviderCatalogService providerCatalogService;
  private final ProviderSelfService providerSelfService;
  private final LeadAgentService leadAgentService;
  private final LeadTimelineService timelineService;
  private final LeadMessageService messageService;
  private final PushNotificationService pushNotificationService;
  private final TelegramNotifyService telegramNotifyService;

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
      ProviderRepository providerRepository,
      ProviderLeadDeclineRepository declineRepository,
      ProviderCatalogService providerCatalogService,
      ProviderSelfService providerSelfService,
      LeadAgentService leadAgentService,
      LeadTimelineService timelineService,
      LeadMessageService messageService,
      PushNotificationService pushNotificationService,
      TelegramNotifyService telegramNotifyService,
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
    this.providerRepository = providerRepository;
    this.declineRepository = declineRepository;
    this.providerCatalogService = providerCatalogService;
    this.providerSelfService = providerSelfService;
    this.leadAgentService = leadAgentService;
    this.timelineService = timelineService;
    this.messageService = messageService;
    this.pushNotificationService = pushNotificationService;
    this.telegramNotifyService = telegramNotifyService;
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
        if (handleRelease(lead, releaseThreshold, releasedToPool)) {
          actions++;
        }
      } else if (sinceContact.toMinutes() >= staleThreshold) {
        if (!timelineService.hasEvent(lead.getId(), STALE_EVENT_TYPE)) {
          handleStale(lead, staleThreshold);
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
  private boolean handleRelease(Lead lead, long thresholdHours, List<Lead> releasedToPool) {
    Provider unresponsive = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
    if (unresponsive == null) {
      log.warn("watchdog: lead {} con assignedProviderId {} sin provider en base, se omite",
          lead.getId(), lead.getAssignedProviderId());
      return false;
    }

    // Contrato §C.1.1.b: decline implícito del contactado ANTES de mirar
    // alternativas — findMatchesForLead ya lo excluye después de esto.
    registerImplicitDecline(lead.getId(), unresponsive.getId());

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
