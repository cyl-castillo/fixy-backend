package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Caso real que lo motiva (2026-07-16): Barométrica Nueva Era hizo el
 * trabajo del lead #105, cobró $2500 y nunca marcó "Completado" en su
 * panel — Carlos tuvo que perseguirlo por WhatsApp y terminó cerrando el
 * lead por ops con el monto que el proveedor le confirmó a mano. Sin cierre
 * no hay evento de comisión: la fricción de que el proveedor no opera su
 * panel pega directo en el modelo de negocio.
 *
 * Dos avisos escalonados para un mismo trabajo asignado que no se cierra:
 * - A las {@code provider-hours} (default 24h): push al proveedor
 *   recordándole marcar completado.
 * - A las {@code ops-hours} (default 48h): Telegram a Carlos para que le dé
 *   un toque manual si el proveedor sigue sin operar el panel.
 *
 * Timestamp de referencia — "cuándo se asignó el trabajo": el evento de
 * timeline más reciente de tipo {@code PROVIDER_ACCEPTED} (el que escribe
 * {@link LeadAssignmentService#acceptForProvider}, único camino para que un
 * lead pase a ASSIGNED). Fallback a {@code Lead.getCreatedAt()} si por
 * algún motivo el evento no está (no debería pasar en flujo normal, pero
 * asignaciones legacy/manuales vía ops pueden no haberlo dejado) — igual
 * conservador, nunca sub-cuenta el tiempo transcurrido.
 *
 * Idempotencia: un evento de timeline por aviso y por lead
 * ({@code PROVIDER_CLOSING_REMINDED} / ya cubierto por
 * {@link TelegramNotifyService#notifyStaleJob} con su propio evento
 * {@code OPS_NOTIFIED_STALE_JOB}), mismo patrón que
 * {@link ReengagementScheduler} y {@link LeadClosingScheduler}.
 *
 * Excluye: leads sin proveedor asignado, disputados, [smoke], y cualquiera
 * ya en COMPLETED/CANCELLED (solo aplica a ASSIGNED/IN_PROGRESS).
 */
@Service
public class ProviderClosingReminderScheduler {

  private static final Logger log = LoggerFactory.getLogger(ProviderClosingReminderScheduler.class);
  static final String PROVIDER_REMINDED_EVENT_TYPE = "PROVIDER_CLOSING_REMINDED";
  private static final String ASSIGNMENT_EVENT_TYPE = "PROVIDER_ACCEPTED";

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final ProviderRepository providerRepository;
  private final LeadTimelineService timelineService;
  private final PushNotificationService pushNotificationService;
  private final TelegramNotifyService telegramNotifyService;
  private final boolean enabled;
  private final long providerHours;
  private final long opsHours;
  private final Clock clock;

  public ProviderClosingReminderScheduler(
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      ProviderRepository providerRepository,
      LeadTimelineService timelineService,
      PushNotificationService pushNotificationService,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.closing-reminder.enabled:true}") boolean enabled,
      @Value("${fixy.closing-reminder.provider-hours:24}") long providerHours,
      @Value("${fixy.closing-reminder.ops-hours:48}") long opsHours,
      Clock clock
  ) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.providerRepository = providerRepository;
    this.timelineService = timelineService;
    this.pushNotificationService = pushNotificationService;
    this.telegramNotifyService = telegramNotifyService;
    this.enabled = enabled;
    this.providerHours = providerHours;
    this.opsHours = opsHours;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.closing-reminder.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int processed = processOnce();
    if (processed > 0) {
      log.info("recordatorio de cierre: {} lead(s) procesados", processed);
    }
  }

  /** Un ciclo del job, invocable desde tests sin esperar al scheduling real.
   * Devuelve cuántos avisos nuevos (proveedor + ops) se dispararon. */
  public int processOnce() {
    List<Lead> candidates = new ArrayList<>();
    candidates.addAll(leadRepository.findByStatusOrderByCreatedAtDesc(LeadStatus.ASSIGNED));
    candidates.addAll(leadRepository.findByStatusOrderByCreatedAtDesc(LeadStatus.IN_PROGRESS));

    int actions = 0;
    OffsetDateTime now = OffsetDateTime.now(clock);
    for (Lead lead : candidates) {
      if (!isEligible(lead)) {
        continue;
      }
      OffsetDateTime assignedAt = findAssignedAt(lead);
      if (assignedAt == null) {
        continue;
      }
      long hoursOpen = Duration.between(assignedAt, now).toHours();

      if (hoursOpen >= providerHours && !timelineService.hasEvent(lead.getId(), PROVIDER_REMINDED_EVENT_TYPE)) {
        remindProvider(lead);
        actions++;
      }
      if (hoursOpen >= opsHours) {
        Provider provider = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
        String providerName = provider != null ? provider.getName() : lead.getAssignedProvider();
        telegramNotifyService.notifyStaleJob(lead, providerName);
      }
    }
    return actions;
  }

  private void remindProvider(Lead lead) {
    Provider provider = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
    if (provider == null) {
      return;
    }
    pushNotificationService.notifyProvider(
        provider.getId(),
        provider.getAccessToken(),
        "¿Terminaste el trabajo?",
        "El trabajo de %s en %s sigue abierto — si ya lo hiciste, marcalo completado en tu panel y registrá lo que cobraste."
            .formatted(
                com.fixy.backend.model.ServiceCategory.humanLabel(lead.getDetectedCategory()),
                safe(lead.getLocation())
            )
    );
    timelineService.appendEvent(lead, PROVIDER_REMINDED_EVENT_TYPE, "system",
        "Recordatorio push al proveedor tras %d horas sin cerrar".formatted(providerHours));
  }

  private boolean isEligible(Lead lead) {
    if (lead.getId() == null || lead.isDisputed()) {
      return false;
    }
    if (lead.getAssignedProviderId() == null) {
      return false;
    }
    String problem = lead.getProblem();
    if (problem != null && problem.toLowerCase(Locale.ROOT).contains("[smoke]")) {
      return false;
    }
    return true;
  }

  private OffsetDateTime findAssignedAt(Lead lead) {
    return leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), ASSIGNMENT_EVENT_TYPE)
        .stream()
        .map(LeadEvent::getCreatedAt)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(lead.getCreatedAt());
  }

  private String safe(String value) {
    return value == null || value.isBlank() ? "sin definir" : value;
  }
}
