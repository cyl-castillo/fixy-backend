package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ServiceCategory;
import com.fixy.backend.model.SmokeTraffic;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
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
 * Diagnóstico 2026-08-17: el auto-match asigna el lead en EXCLUSIVA a un solo
 * proveedor ({@code LeadAgentService.contactTopMatch}) y, mientras ese
 * proveedor no responde, la bandeja pública lo esconde del resto
 * ({@code ProviderOpportunityService.listFor} excluye
 * {@code assignedProviderId != null}). {@link MatchingStaleScheduler} avisa
 * UNA vez a los 45 min pero no reasigna — resultado real en prod: ~18 leads
 * sin aceptar en 4 días.
 *
 * Esta clase cierra ese camino muerto: tras {@code auto-release.hours} sin
 * que el proveedor contactado acepte NI rechace, el lead se libera al pozo
 * abierto (reusa {@link ProviderSelfService#releaseAfterTimeout} — mismo
 * mecanismo de limpieza que {@code releaseAfterProviderCancel}, sin registrar
 * {@link com.fixy.backend.model.ProviderLeadDecline}: el proveedor que no
 * respondió puede haber estado ocupado, así que sigue viendo el lead en su
 * bandeja). El primero que acepta después se lo queda — mismo criterio que
 * cualquier oportunidad broadcast.
 *
 * Idempotente por diseño sin evento de "ya procesado": una vez liberado, el
 * lead deja de cumplir la condición (status vuelve a NEW, ya no
 * PROVIDER_CONTACTED con este proveedor), así que no se reprocesa en la
 * misma corrida. Si se reasigna y vuelve a quedar colgado, puede volver a
 * liberarse en una corrida futura — es la gracia del mecanismo.
 *
 * Avisos: push a hasta 5 proveedores candidatos de la zona/categoría (mismo
 * patrón que {@link MatchingStaleScheduler#remindProviders}) y UN resumen
 * por Telegram al final de la corrida si liberó algo (throttle "por corrida",
 * no por lead — ver {@link TelegramNotifyService#notifyAutoReleaseSummary}).
 */
@Service
public class MatchingAutoReleaseScheduler {

  private static final Logger log = LoggerFactory.getLogger(MatchingAutoReleaseScheduler.class);
  private static final String PROVIDER_CONTACTED_EVENT_TYPE = "PROVIDER_CONTACTED";
  /** Tope de pushes a proveedores candidatos por lead liberado — recordatorio, no spam masivo. */
  private static final int MAX_PROVIDER_PUSHES = 5;

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final ProviderRepository providerRepository;
  private final ProviderCatalogService providerCatalogService;
  private final ProviderSelfService providerSelfService;
  private final PushNotificationService pushNotificationService;
  private final TelegramNotifyService telegramNotifyService;
  private final boolean enabled;
  private final long hours;
  private final Clock clock;

  public MatchingAutoReleaseScheduler(
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      ProviderRepository providerRepository,
      ProviderCatalogService providerCatalogService,
      ProviderSelfService providerSelfService,
      PushNotificationService pushNotificationService,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.matching.auto-release.enabled:true}") boolean enabled,
      @Value("${fixy.matching.auto-release.hours:12}") long hours,
      Clock clock
  ) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.providerRepository = providerRepository;
    this.providerCatalogService = providerCatalogService;
    this.providerSelfService = providerSelfService;
    this.pushNotificationService = pushNotificationService;
    this.telegramNotifyService = telegramNotifyService;
    this.enabled = enabled;
    this.hours = hours;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.matching.auto-release.scheduler-fixed-delay-ms:900000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int released = processOnce();
    if (released > 0) {
      log.info("auto-liberación de matching: {} lead(s) liberados al pozo", released);
    }
  }

  /** Un ciclo del job, invocable desde tests. Devuelve cuántos leads se liberaron. */
  public int processOnce() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<Lead> candidates = leadRepository.findByStatusOrderByCreatedAtDesc(LeadStatus.PROVIDER_CONTACTED);

    List<Lead> released = new ArrayList<>();
    for (Lead lead : candidates) {
      if (!isEligible(lead)) {
        continue;
      }
      OffsetDateTime contactedAt = lastContactedAt(lead);
      if (contactedAt == null || Duration.between(contactedAt, now).toHours() < hours) {
        continue;
      }
      Provider unresponsive = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
      if (unresponsive == null) {
        log.warn("auto-release: lead {} con assignedProviderId {} sin provider en base, se omite",
            lead.getId(), lead.getAssignedProviderId());
        continue;
      }
      try {
        providerSelfService.releaseAfterTimeout(lead, unresponsive);
      } catch (Exception ex) {
        log.warn("auto-release: no se pudo liberar el lead {}: {}", lead.getId(), ex.getMessage());
        continue;
      }
      released.add(lead);
      try {
        remindCandidates(lead);
      } catch (Exception ex) {
        log.warn("auto-release: push a candidatos del lead {} falló: {}", lead.getId(), ex.getMessage());
      }
    }

    if (!released.isEmpty()) {
      telegramNotifyService.notifyAutoReleaseSummary(released, hours);
    }
    return released.size();
  }

  private boolean isEligible(Lead lead) {
    if (lead.getId() == null || lead.isDisputed()) {
      return false;
    }
    if (lead.getAssignedProviderId() == null) {
      return false;
    }
    if (SmokeTraffic.marks(lead.getProblem())) {
      return false;
    }
    // ASSIGNED/IN_PROGRESS/COMPLETED/CANCELLED ya salieron del filtro por
    // status en la query; PROVIDER_CONTACTED es justamente "contactado, sin
    // aceptar ni rechazar todavía".
    return lead.getStatus() == LeadStatus.PROVIDER_CONTACTED;
  }

  /** Último PROVIDER_CONTACTED del lead — si se reintenta con otro proveedor, reinicia el reloj. */
  private OffsetDateTime lastContactedAt(Lead lead) {
    return leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), PROVIDER_CONTACTED_EVENT_TYPE)
        .stream()
        .map(LeadEvent::getCreatedAt)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
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

  private String safe(String value) {
    return value == null || value.isBlank() ? "sin definir" : value;
  }
}
