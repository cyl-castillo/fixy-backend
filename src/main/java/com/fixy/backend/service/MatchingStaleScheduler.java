package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ServiceCategory;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * "Nunca más camino muerto" (hallazgo 2026-07-21): un pedido listo para
 * matching que nadie aceptaba quedaba esperando PARA SIEMPRE — el cliente
 * con un "te avisamos apenas uno confirme" que nunca llegaba, sin
 * recordatorio a proveedores y sin alerta a ops. Ningún scheduler vigilaba
 * este tramo (Reengagement solo cubre intake NEW/IN_REVIEW sin matching;
 * ClosingReminder solo trabajos ya aceptados).
 *
 * Cubre los dos caminos muertos posibles:
 * - Broadcast ({@code LeadService.generateMatches}): readyForMatching, sin
 *   {@code assignedProviderId} — visible en bandejas pero nadie lo tomó.
 * - Auto-match ({@code LeadAgentService.tryAutoMatch}): PROVIDER_CONTACTED
 *   con {@code assignedProviderId} — el proveedor contactado nunca respondió.
 *
 * Tras {@code threshold-minutes} sin aceptación (medidos desde el último
 * evento MATCH_GENERATED/PROVIDER_CONTACTED), una sola vez por lead
 * (evento {@code MATCHING_STALE_NOTIFIED}, mismo patrón de idempotencia
 * que {@link ProviderClosingReminderScheduler}):
 * 1. Mensaje honesto al cliente en el chat (postFromOps ya incluye push).
 * 2. Push a los proveedores que matchean (o al contactado puntual).
 * 3. Telegram a ops vía {@link TelegramNotifyService#notifyStaleMatching}.
 */
@Service
public class MatchingStaleScheduler {

  private static final Logger log = LoggerFactory.getLogger(MatchingStaleScheduler.class);
  static final String STALE_EVENT_TYPE = "MATCHING_STALE_NOTIFIED";
  private static final Set<String> MATCHING_STARTED_EVENT_TYPES = Set.of("MATCH_GENERATED", "PROVIDER_CONTACTED");
  /** Mismos estados "abiertos" que la bandeja de oportunidades (ProviderOpportunityService.OPEN_STATUSES). */
  private static final Set<LeadStatus> OPEN_STATUSES =
      Set.of(LeadStatus.NEW, LeadStatus.IN_REVIEW, LeadStatus.PROVIDER_CONTACTED);
  /** Tope de pushes a proveedores por lead — recordatorio, no spam masivo. */
  private static final int MAX_PROVIDER_PUSHES = 5;

  static final String CUSTOMER_MESSAGE =
      "Tu pedido está demorando más de lo normal en conseguir proveedor. Lo seguimos moviendo y ya "
          + "avisamos a una persona de Fixy para que lo mire — te escribimos apenas haya novedades.";

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final ProviderRepository providerRepository;
  private final ProviderCatalogService providerCatalogService;
  private final LeadTimelineService timelineService;
  private final LeadMessageService messageService;
  private final PushNotificationService pushNotificationService;
  private final TelegramNotifyService telegramNotifyService;
  private final boolean enabled;
  private final long thresholdMinutes;
  private final Clock clock;

  public MatchingStaleScheduler(
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      ProviderRepository providerRepository,
      ProviderCatalogService providerCatalogService,
      LeadTimelineService timelineService,
      LeadMessageService messageService,
      PushNotificationService pushNotificationService,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.stale-matching.enabled:true}") boolean enabled,
      @Value("${fixy.stale-matching.threshold-minutes:45}") long thresholdMinutes,
      Clock clock
  ) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.providerRepository = providerRepository;
    this.providerCatalogService = providerCatalogService;
    this.timelineService = timelineService;
    this.messageService = messageService;
    this.pushNotificationService = pushNotificationService;
    this.telegramNotifyService = telegramNotifyService;
    this.enabled = enabled;
    this.thresholdMinutes = thresholdMinutes;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.stale-matching.scheduler-fixed-delay-ms:600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int processed = processOnce();
    if (processed > 0) {
      log.info("matching estancado: {} lead(s) notificados", processed);
    }
  }

  /** Un ciclo del job, invocable desde tests sin esperar al scheduling real. Devuelve cuántos leads se notificaron. */
  public int processOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<Lead> candidates = new ArrayList<>();
    for (LeadStatus status : OPEN_STATUSES) {
      candidates.addAll(leadRepository.findByStatusOrderByCreatedAtDesc(status));
    }

    int notified = 0;
    for (Lead lead : candidates) {
      if (!isEligible(lead)) {
        continue;
      }
      OffsetDateTime matchingStartedAt = findMatchingStartedAt(lead);
      if (matchingStartedAt == null) {
        // Nunca llegó a matching (sigue en intake) — territorio del ReengagementScheduler.
        continue;
      }
      if (Duration.between(matchingStartedAt, now).toMinutes() < thresholdMinutes) {
        continue;
      }
      if (timelineService.hasEvent(lead.getId(), STALE_EVENT_TYPE)) {
        continue;
      }
      notifyStale(lead);
      notified++;
    }
    return notified;
  }

  private void notifyStale(Lead lead) {
    // 1. Cliente: mensaje honesto en el chat — postFromOps ya dispara el push
    // "tenés novedades" y el evento MESSAGE_FROM_FIXY.
    try {
      messageService.postFromOps(lead.getId(), "fixy", CUSTOMER_MESSAGE);
    } catch (Exception ex) {
      log.warn("stale-matching: no se pudo avisar al cliente del lead {}: {}", lead.getId(), ex.getMessage());
    }

    // 2. Proveedores: recordatorio push. Contactado puntual si lo hay;
    // si es broadcast, a los que matchean por categoría/zona (con tope).
    try {
      remindProviders(lead);
    } catch (Exception ex) {
      log.warn("stale-matching: push a proveedores del lead {} falló: {}", lead.getId(), ex.getMessage());
    }

    // 3. Ops por Telegram (idempotencia propia vía OPS_NOTIFIED_STALE_MATCHING).
    telegramNotifyService.notifyStaleMatching(lead, thresholdMinutes);

    timelineService.appendEvent(lead, STALE_EVENT_TYPE, "system",
        "Sin proveedor tras %d min: se avisó a cliente, proveedores y ops".formatted(thresholdMinutes));
  }

  private void remindProviders(Lead lead) {
    String title = "¿Podés tomar este trabajo?";
    String body = "Sigue disponible: %s en %s — entrá a tu panel y decidí con un toque."
        .formatted(ServiceCategory.humanLabel(lead.getDetectedCategory()), safe(lead.getLocation()));

    if (lead.getAssignedProviderId() != null) {
      Provider contacted = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
      if (contacted != null) {
        pushNotificationService.notifyProvider(contacted.getId(), contacted.getAccessToken(), title, body);
      }
      return;
    }

    providerRepository.findAll().stream()
        .filter(p -> p.getAcceptingWork() == null || p.getAcceptingWork())
        .filter(p -> providerCatalogService.matchesProvider(p, lead.getDetectedCategory(), lead.getLocation()))
        .sorted(Comparator.comparing(Provider::getId))
        .limit(MAX_PROVIDER_PUSHES)
        .forEach(p -> pushNotificationService.notifyProvider(p.getId(), p.getAccessToken(), title, body));
  }

  private boolean isEligible(Lead lead) {
    if (lead.getId() == null || lead.isDisputed()) {
      return false;
    }
    String problem = lead.getProblem();
    if (problem != null && problem.toLowerCase(Locale.ROOT).contains("[smoke]")) {
      return false;
    }
    // Broadcast: solo listos para matching. Contactado puntual: el status
    // PROVIDER_CONTACTED ya implica que el matching arrancó.
    if (lead.getAssignedProviderId() == null) {
      return lead.isReadyForMatching();
    }
    return lead.getStatus() == LeadStatus.PROVIDER_CONTACTED;
  }

  /** Último evento que marca el arranque del matching; null si nunca arrancó. */
  private OffsetDateTime findMatchingStartedAt(Lead lead) {
    return MATCHING_STARTED_EVENT_TYPES.stream()
        .flatMap(type -> leadEventRepository
            .findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), type)
            .stream())
        .map(LeadEvent::getCreatedAt)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(null);
  }

  private String safe(String value) {
    return value == null || value.isBlank() ? "sin definir" : value;
  }
}
