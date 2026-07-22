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
 * H1.4 (follow-up de pagos): recordatorio AMISTOSO de comisión a las
 * {@code reminder-hours} (default 48) — el paso previo a la escalada dura
 * de {@link CommissionOverdueScheduler} (7 días → matching pausado). Tono
 * de recordatorio, no de castigo: la mayoría simplemente se olvidó.
 *
 * Canales: push al proveedor + mensaje en su chat con audiencia
 * {@code provider_only} (información financiera del proveedor, el cliente
 * no la ve — mismo criterio que el aviso de comisión de CommissionService).
 * El playbook original decía WhatsApp: sin credenciales de Meta todavía,
 * el canal WhatsApp se suma acá el día que se prenda — la mecánica
 * (elegibilidad, idempotencia, copy) ya queda resuelta.
 *
 * Mismas reglas de freno que la escalada (disputa sin resolver y [smoke]
 * no reciben nada) y misma cortesía con la primera comisión del proveedor
 * (el doble de plazo antes de recordar). Idempotente: un solo recordatorio
 * por lead (evento {@code COMMISSION_REMINDED}).
 */
@Service
public class CommissionReminderScheduler {

  private static final Logger log = LoggerFactory.getLogger(CommissionReminderScheduler.class);
  static final String REMINDED_EVENT_TYPE = "COMMISSION_REMINDED";

  private final LeadPaymentRepository leadPaymentRepository;
  private final LeadRepository leadRepository;
  private final ProviderRepository providerRepository;
  private final LeadTimelineService timelineService;
  private final LeadMessageService messageService;
  private final PushNotificationService pushNotificationService;
  private final boolean enabled;
  private final long reminderHours;
  private final Clock clock;

  public CommissionReminderScheduler(
      LeadPaymentRepository leadPaymentRepository,
      LeadRepository leadRepository,
      ProviderRepository providerRepository,
      LeadTimelineService timelineService,
      LeadMessageService messageService,
      PushNotificationService pushNotificationService,
      @Value("${fixy.collections.reminder-enabled:true}") boolean enabled,
      @Value("${fixy.collections.reminder-hours:48}") long reminderHours,
      Clock clock
  ) {
    this.leadPaymentRepository = leadPaymentRepository;
    this.leadRepository = leadRepository;
    this.providerRepository = providerRepository;
    this.timelineService = timelineService;
    this.messageService = messageService;
    this.pushNotificationService = pushNotificationService;
    this.enabled = enabled;
    this.reminderHours = reminderHours;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.collections.reminder-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int reminded = processOnce();
    if (reminded > 0) {
      log.info("recordatorio de comisión: {} proveedor(es) recordados", reminded);
    }
  }

  /** Un ciclo del job, invocable desde tests. Devuelve cuántos recordatorios se mandaron. */
  public int processOnce() {
    List<LeadPayment> pending =
        leadPaymentRepository.findByCommissionStatusOrderByCreatedAtDesc(CommissionStatus.PENDING);

    int reminded = 0;
    OffsetDateTime now = OffsetDateTime.now(clock);
    for (LeadPayment payment : pending) {
      if (!isEligible(payment, now)) {
        continue;
      }
      if (timelineService.hasEvent(payment.getLeadId(), REMINDED_EVENT_TYPE)) {
        continue;
      }
      remind(payment);
      reminded++;
    }
    return reminded;
  }

  private boolean isEligible(LeadPayment payment, OffsetDateTime now) {
    if (payment.getCreatedAt() == null) {
      return false;
    }
    Lead lead = leadRepository.findById(payment.getLeadId()).orElse(null);
    if (lead == null) {
      return false;
    }
    if (lead.isDisputed() && lead.getDisputeResolvedAt() == null) {
      return false; // misma regla que la escalada: disputa sin resolver frena todo
    }
    String problem = lead.getProblem();
    if (problem != null && problem.toLowerCase(Locale.ROOT).contains("[smoke]")) {
      return false;
    }
    long threshold = isFirstCommission(payment.getProviderId()) ? reminderHours * 2 : reminderHours;
    long hoursOpen = Duration.between(payment.getCreatedAt(), now).toHours();
    return hoursOpen >= threshold;
  }

  /** Misma cortesía que CommissionOverdueScheduler: la primera comisión tolera el doble de plazo. */
  private boolean isFirstCommission(Long providerId) {
    return leadPaymentRepository.countByProviderId(providerId) <= 1;
  }

  private void remind(LeadPayment payment) {
    Lead lead = leadRepository.findById(payment.getLeadId()).orElse(null);
    Provider provider = providerRepository.findById(payment.getProviderId()).orElse(null);
    if (lead == null || provider == null) {
      return;
    }

    String amount = "%s %s".formatted(payment.getCurrency(), payment.getCommissionAmount());
    String payHint = payment.getMpPaymentLink() != null
        ? "El botón Pagar está en tu panel — se salda por Mercado Pago al momento."
        : "Coordinala con Fixy cuando puedas.";
    String chatMessage = "Recordatorio: la comisión de %s del trabajo #%d sigue pendiente. %s"
        .formatted(amount, lead.getId(), payHint);

    // provider_only: información financiera del proveedor, el cliente no la ve.
    try {
      messageService.postFromOps(lead.getId(), "fixy", chatMessage, "provider_only");
    } catch (Exception ex) {
      log.warn("recordatorio de comisión: mensaje al chat del lead {} falló: {}", lead.getId(), ex.getMessage());
    }
    try {
      pushNotificationService.notifyProvider(
          provider.getId(),
          provider.getAccessToken(),
          "Recordatorio de comisión",
          "La comisión de %s del trabajo de %s sigue pendiente. %s"
              .formatted(amount, com.fixy.backend.model.ServiceCategory.humanLabel(lead.getDetectedCategory()), payHint)
      );
    } catch (Exception ex) {
      log.warn("recordatorio de comisión: push al proveedor {} falló: {}", provider.getId(), ex.getMessage());
    }

    timelineService.appendEvent(lead, REMINDED_EVENT_TYPE, "system",
        "Recordatorio amistoso de comisión pendiente a las %d horas".formatted(reminderHours));
  }
}
