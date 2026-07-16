package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
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
 * Re-enganche de clientes que abren el chat y se quedan mudos, o cortan el
 * intake a mitad. Caso real que lo motiva (2026-07-16): los leads #116 y
 * #118 — visitantes orgánicos que abrieron el chat y nunca completaron los
 * datos; el agente no hacía nada y el lead moría en silencio.
 *
 * Reglas (conservadoras a propósito — un empujón, no spam):
 * - UNA sola vez por lead (evento de timeline {@code REENGAGEMENT_NUDGE},
 *   mismo patrón de idempotencia que OPS_NOTIFIED_OPPORTUNITY).
 * - Solo leads jóvenes (menos de {@code max-age-hours}) — no resucitar
 *   conversaciones viejas.
 * - Solo si el ÚLTIMO mensaje es del agente y pasaron
 *   {@code silence-minutes} — si el último es del cliente, el que debe una
 *   respuesta es el agente, no el cliente.
 * - Nunca en leads listos para matching (ya tienen su "te aviso"), con
 *   proveedor asignado (conversación humana), disputados o [smoke].
 *
 * El mensaje sale por {@link LeadMessageService#postFromAgent}, así que si
 * el cliente activó las notificaciones push, el empujón le llega al celular
 * aunque haya cerrado la pestaña.
 */
@Service
public class ReengagementScheduler {

  private static final Logger log = LoggerFactory.getLogger(ReengagementScheduler.class);
  static final String NUDGED_EVENT_TYPE = "REENGAGEMENT_NUDGE";
  private static final int HISTORY_LIMIT = 20;

  private final LeadRepository leadRepository;
  private final LeadMessageService leadMessageService;
  private final LeadTimelineService timelineService;
  private final boolean enabled;
  private final long silenceMinutes;
  private final long maxAgeHours;
  private final Clock clock;

  public ReengagementScheduler(
      LeadRepository leadRepository,
      LeadMessageService leadMessageService,
      LeadTimelineService timelineService,
      @Value("${fixy.reengagement.enabled:true}") boolean enabled,
      @Value("${fixy.reengagement.silence-minutes:10}") long silenceMinutes,
      @Value("${fixy.reengagement.max-age-hours:24}") long maxAgeHours,
      Clock clock
  ) {
    this.leadRepository = leadRepository;
    this.leadMessageService = leadMessageService;
    this.timelineService = timelineService;
    this.enabled = enabled;
    this.silenceMinutes = silenceMinutes;
    this.maxAgeHours = maxAgeHours;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.reengagement.scheduler-fixed-delay-ms:300000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int nudged = processOnce();
    if (nudged > 0) {
      log.info("re-enganche: {} lead(s) empujados", nudged);
    }
  }

  /** Un ciclo del job, invocable desde tests sin esperar al scheduling real. */
  public int processOnce() {
    List<Lead> candidates = new ArrayList<>();
    candidates.addAll(leadRepository.findByStatusOrderByCreatedAtDesc(LeadStatus.NEW));
    candidates.addAll(leadRepository.findByStatusOrderByCreatedAtDesc(LeadStatus.IN_REVIEW));

    int nudged = 0;
    OffsetDateTime now = OffsetDateTime.now(clock);
    for (Lead lead : candidates) {
      if (!isNudgeable(lead, now)) {
        continue;
      }
      List<LeadMessage> recent = leadMessageService.recentForAgent(lead.getId(), HISTORY_LIMIT);
      if (recent.isEmpty()) {
        continue;
      }
      LeadMessage last = recent.get(recent.size() - 1);
      if (!"fixy".equals(last.getSender())) {
        continue; // el último habló el cliente: la respuesta la debe el agente
      }
      if (last.getCreatedAt() == null
          || Duration.between(last.getCreatedAt(), now).toMinutes() < silenceMinutes) {
        continue; // todavía no hay silencio suficiente
      }
      boolean customerEverWrote = recent.stream().anyMatch(m -> "customer".equals(m.getSender()));
      leadMessageService.postFromAgent(lead.getId(), nudgeText(lead, customerEverWrote));
      timelineService.appendEvent(lead, NUDGED_EVENT_TYPE, "system",
          "Empujón de re-enganche tras %d min de silencio".formatted(silenceMinutes));
      nudged++;
    }
    return nudged;
  }

  private boolean isNudgeable(Lead lead, OffsetDateTime now) {
    if (lead.getId() == null || lead.isDisputed() || lead.isReadyForMatching()) {
      return false;
    }
    if (lead.getAssignedProviderId() != null) {
      return false;
    }
    String problem = lead.getProblem();
    if (problem != null && problem.toLowerCase(Locale.ROOT).contains("[smoke]")) {
      return false;
    }
    if (lead.getCreatedAt() == null
        || Duration.between(lead.getCreatedAt(), now).toHours() >= maxAgeHours) {
      return false;
    }
    return !timelineService.hasEvent(lead.getId(), NUDGED_EVENT_TYPE);
  }

  private String nudgeText(Lead lead, boolean customerEverWrote) {
    if (!customerEverWrote) {
      return "¿Seguís por ahí? Contame qué necesitás — un arreglo, un servicio para tu casa, "
          + "una torta para un cumple — y te consigo un proveedor de tu zona. Sin compromiso.";
    }
    String category = lead.getDetectedCategory();
    if (category != null && !category.isBlank() && !"otro".equalsIgnoreCase(category)) {
      return "¿Seguís por ahí? Tu pedido de %s quedó a medias — cuando puedas pasame lo que falta y sigo buscándote proveedor."
          .formatted(com.fixy.backend.model.ServiceCategory.humanLabel(category));
    }
    return "¿Seguís por ahí? Me quedó tu pedido a medias — contame un poco más y sigo con la búsqueda.";
  }
}
