package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * H2.4: auto-confirmación NEUTRAL cuando el cliente no responde en
 * {@code fixy.closing.auto-confirm-hours} (default 72h) tras el COMPLETED
 * del proveedor. "Neutral" = no crea {@link com.fixy.backend.model.LeadRating}
 * ni toca los agregados del proveedor (ratingAverage/ratingCount) — solo
 * deja constancia en el timeline. Decisión de Carlos: no forzar un rating
 * que el cliente no dio.
 *
 * Fuente del timestamp de "cuándo se completó": el {@link LeadEvent} más
 * reciente de tipo {@code PROVIDER_STATUS_CHANGE} cuyo mensaje termina en
 * "→ COMPLETED" (formato emitido por {@link ProviderSelfService}). Se
 * eligió el evento y no {@code Lead.updatedAt} porque updatedAt se pisa
 * con cualquier otro cambio del lead (notas, reasignación, etc. desde
 * ops) — el evento es inmutable y específico de esa transición.
 *
 * Idempotencia: {@code Lead.closingAutoConfirmedAt} se setea una sola vez;
 * el finder de candidatos ya excluye leads con ese campo no nulo.
 */
@Service
public class LeadClosingScheduler {

  private static final Logger log = LoggerFactory.getLogger(LeadClosingScheduler.class);
  private static final String STATUS_CHANGE_EVENT_TYPE = "PROVIDER_STATUS_CHANGE";
  private static final String COMPLETED_SUFFIX = "→ " + LeadStatus.COMPLETED;

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final LeadRatingRepository leadRatingRepository;
  private final LeadTimelineService timelineService;
  private final boolean enabled;
  private final long autoConfirmHours;
  private final Clock clock;

  public LeadClosingScheduler(
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      LeadRatingRepository leadRatingRepository,
      LeadTimelineService timelineService,
      @Value("${fixy.closing.enabled:true}") boolean enabled,
      @Value("${fixy.closing.auto-confirm-hours:72}") long autoConfirmHours,
      Clock clock
  ) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.leadRatingRepository = leadRatingRepository;
    this.timelineService = timelineService;
    this.enabled = enabled;
    this.autoConfirmHours = autoConfirmHours;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.closing.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int processed = processOnce();
    if (processed > 0) {
      log.info("auto-confirm 72h: {} lead(s) procesados", processed);
    }
  }

  /** Un ciclo del job, invocable directamente desde tests sin esperar al
   * scheduling real. Devuelve cuántos leads se auto-confirmaron. */
  public int processOnce() {
    List<Lead> candidates = leadRepository
        .findByStatusAndDisputedFalseAndClosingAutoConfirmedAtIsNull(LeadStatus.COMPLETED);
    int processed = 0;
    OffsetDateTime now = OffsetDateTime.now(clock);
    for (Lead lead : candidates) {
      if (leadRatingRepository.existsByLeadId(lead.getId())) {
        continue; // ya confirmado con rating real, no pisar
      }
      OffsetDateTime completedAt = findCompletedAt(lead.getId());
      if (completedAt == null) {
        continue; // no debería pasar (status COMPLETED implica el evento), skip defensivo
      }
      if (Duration.between(completedAt, now).toHours() < autoConfirmHours) {
        continue; // todavía no pasaron las N horas
      }
      lead.setClosingAutoConfirmedAt(now);
      leadRepository.save(lead);
      timelineService.appendEvent(lead, "COMPLETION_AUTO_CONFIRMED", "system",
          "Sin respuesta del cliente tras %d horas; auto-confirmado sin calificación".formatted(autoConfirmHours));
      processed++;
    }
    return processed;
  }

  private OffsetDateTime findCompletedAt(Long leadId) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, STATUS_CHANGE_EVENT_TYPE)
        .stream()
        .filter(event -> event.getMessage() != null && event.getMessage().endsWith(COMPLETED_SUFFIX))
        .map(LeadEvent::getCreatedAt)
        .findFirst()
        .orElse(null);
  }
}
