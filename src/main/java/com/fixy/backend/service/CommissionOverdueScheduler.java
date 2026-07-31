package com.fixy.backend.service;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * "Comisión vencida pausa el matching" (FIXY_COBRANZAS.md, sección
 * "Mecánica de producto propuesta"): a los {@code overdue-days} (default 7)
 * una comisión {@code PENDING} pasa a {@code OVERDUE}. Es la escalada
 * automática de la T+7 días del playbook — completa la palanca honesta:
 * Fixy no presiona, solo deja de mandar pedidos nuevos hasta que se salda.
 *
 * Reglas del playbook (innegociables, ver FIXY_COBRANZAS.md):
 * - Primera comisión del proveedor (su único {@link LeadPayment}): tolera
 *   el doble de plazo (14 días) — está aprendiendo el sistema.
 * - Lead disputado sin resolver: la escalera SE FRENA, no vence.
 * - Leads {@code [smoke]}: nunca vencen (tráfico de prueba).
 *
 * Idempotencia: la transición PENDING→OVERDUE es el guard natural — el
 * push y el evento de timeline solo se emiten en esa transición, nunca en
 * un ciclo posterior sobre una comisión que ya está OVERDUE.
 *
 * Mismo patrón que {@link ReengagementScheduler} /
 * {@link ProviderClosingReminderScheduler}: @Scheduled con fixedDelay
 * configurable, processOnce() invocable desde tests, Clock inyectado.
 */
@Service
public class CommissionOverdueScheduler {

  private static final Logger log = LoggerFactory.getLogger(CommissionOverdueScheduler.class);
  static final String OVERDUE_EVENT_TYPE = "COMMISSION_OVERDUE";

  private final LeadPaymentRepository leadPaymentRepository;
  private final LeadRepository leadRepository;
  private final ProviderRepository providerRepository;
  private final LeadTimelineService timelineService;
  private final PushNotificationService pushNotificationService;
  private final boolean enabled;
  private final long overdueDays;
  private final Clock clock;

  public CommissionOverdueScheduler(
      LeadPaymentRepository leadPaymentRepository,
      LeadRepository leadRepository,
      ProviderRepository providerRepository,
      LeadTimelineService timelineService,
      PushNotificationService pushNotificationService,
      @Value("${fixy.collections.enabled:true}") boolean enabled,
      @Value("${fixy.collections.overdue-days:7}") long overdueDays,
      Clock clock
  ) {
    this.leadPaymentRepository = leadPaymentRepository;
    this.leadRepository = leadRepository;
    this.providerRepository = providerRepository;
    this.timelineService = timelineService;
    this.pushNotificationService = pushNotificationService;
    this.enabled = enabled;
    this.overdueDays = overdueDays;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.collections.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int overdue = processOnce();
    if (overdue > 0) {
      log.info("comisiones vencidas: {} pasaron a OVERDUE", overdue);
    }
  }

  /** Un ciclo del job, invocable desde tests sin esperar al scheduling
   * real. Devuelve cuántas comisiones pasaron a OVERDUE en este ciclo. */
  public int processOnce() {
    List<LeadPayment> pending =
        leadPaymentRepository.findByCommissionStatusOrderByCreatedAtDesc(CommissionStatus.PENDING);

    int overdue = 0;
    OffsetDateTime now = OffsetDateTime.now(clock);
    for (LeadPayment payment : pending) {
      if (!isOverdue(payment, now)) {
        continue;
      }
      payment.setCommissionStatus(CommissionStatus.OVERDUE);
      leadPaymentRepository.save(payment);
      notifyOverdue(payment);
      overdue++;
    }
    return overdue;
  }

  private boolean isOverdue(LeadPayment payment, OffsetDateTime now) {
    if (payment.getCreatedAt() == null) {
      return false;
    }
    Lead lead = leadRepository.findById(payment.getLeadId()).orElse(null);
    if (lead == null) {
      return false;
    }
    if (lead.isDisputed() && lead.getDisputeResolvedAt() == null) {
      return false; // disputa sin resolver: la escalera se frena
    }
    String problem = lead.getProblem();
    if (com.fixy.backend.model.SmokeTraffic.marks(problem)) {
      return false;
    }

    long threshold = isFirstCommission(payment.getProviderId()) ? overdueDays * 2 : overdueDays;
    long daysOpen = Duration.between(payment.getCreatedAt(), now).toDays();
    return daysOpen >= threshold;
  }

  /** Primera comisión del proveedor = tiene un único LeadPayment (este
   * mismo). Tolera el doble de plazo: está aprendiendo el sistema. */
  private boolean isFirstCommission(Long providerId) {
    return leadPaymentRepository.countByProviderId(providerId) <= 1;
  }

  private void notifyOverdue(LeadPayment payment) {
    Lead lead = leadRepository.findById(payment.getLeadId()).orElse(null);
    if (lead != null) {
      timelineService.appendEvent(lead, OVERDUE_EVENT_TYPE, "system",
          "Comisión %s %s venció sin pagar tras %d día(s) — matching pausado hasta saldar"
              .formatted(payment.getCurrency(), payment.getCommissionAmount(), overdueDays));
    }

    Provider provider = providerRepository.findById(payment.getProviderId()).orElse(null);
    if (provider == null) {
      return;
    }
    String category = lead != null ? lead.getDetectedCategory() : null;
    String categoryLabel = com.fixy.backend.model.ServiceCategory.humanLabel(category);
    pushNotificationService.notifyProvider(
        provider.getId(),
        provider.getAccessToken(),
        "Tenés una comisión pendiente",
        "Comisión de %s %s del trabajo de %s: al saldarla volvés a recibir pedidos al instante. El botón Pagar está en tu panel."
            .formatted(payment.getCurrency(), payment.getCommissionAmount(), categoryLabel)
    );
  }
}
