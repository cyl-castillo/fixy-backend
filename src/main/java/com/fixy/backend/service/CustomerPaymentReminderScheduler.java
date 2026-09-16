package com.fixy.backend.service;

import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Contrato §A.4.5 (Tier 1 §C.2 lo acorta de 48h a 24h): recordatorio único
 * del cargo de servicio. Corre cada hora; sobre cargos SERVICE_FEE PENDING
 * con link, creados hace más de {@link #DUE_AFTER_HOURS} y nunca recordados,
 * manda un mensaje amable UNA sola vez. Nunca cambia el status — el vecino
 * no paga, no pasa nada (doctrina del contrato: "nunca castigar por no
 * pagar").
 */
@Service
public class CustomerPaymentReminderScheduler {

  private static final Logger log = LoggerFactory.getLogger(CustomerPaymentReminderScheduler.class);
  /** Tier 1 (contrato §C.2): era 48h, ahora 24h. */
  private static final long DUE_AFTER_HOURS = 24;
  /** A los 7 días sin pagar se le avisa a ops UNA vez (dato, no cobranza). */
  static final long UNPAID_NOTIFY_AFTER_DAYS = 7;

  private final CustomerPaymentQueryService customerPaymentQueryService;
  private final LeadMessageService leadMessageService;
  private final CustomerPaymentRepository customerPaymentRepository;
  private final LeadRepository leadRepository;
  private final TelegramNotifyService telegramNotifyService;
  private final boolean enabled;
  private final Clock clock;

  public CustomerPaymentReminderScheduler(
      CustomerPaymentQueryService customerPaymentQueryService,
      LeadMessageService leadMessageService,
      CustomerPaymentRepository customerPaymentRepository,
      LeadRepository leadRepository,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.customer-payments.reminder.enabled:true}") boolean enabled,
      Clock clock
  ) {
    this.customerPaymentQueryService = customerPaymentQueryService;
    this.leadMessageService = leadMessageService;
    this.customerPaymentRepository = customerPaymentRepository;
    this.leadRepository = leadRepository;
    this.telegramNotifyService = telegramNotifyService;
    this.enabled = enabled;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${fixy.customer-payments.reminder.scheduler-fixed-delay-ms:3600000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int reminded = processOnce();
    if (reminded > 0) {
      log.info("recordatorio de cargo de servicio: {} cliente(s) recordados", reminded);
    }
  }

  /** Un ciclo del job, invocable desde tests. Devuelve cuántos se recordaron. */
  public int processOnce() {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minusHours(DUE_AFTER_HOURS);
    List<CustomerPayment> due = customerPaymentQueryService.findServiceFeeDueForReminder(cutoff);
    int reminded = 0;
    for (CustomerPayment payment : due) {
      if (payment.getLeadId() == null) {
        continue;
      }
      try {
        // Tier 1 (contrato §C.2): texto nuevo, con el monto explícito y sin
        // presión ("si preferís no hacerlo, no pasa nada" — misma doctrina
        // que el resto del recordatorio).
        String amountText = ServiceCatalogService.formatUyu(
            payment.getAmount().setScale(0, java.math.RoundingMode.HALF_UP).intValueExact());
        leadMessageService.postFromOps(payment.getLeadId(), "fixy",
            "Todavía podés activar la garantía Fixy de este trabajo: $%s → %s. Si preferís no hacerlo, no pasa nada."
                .formatted(amountText, payment.getMpPaymentLink()));
        customerPaymentQueryService.markReminded(payment.getId());
        reminded++;
      } catch (Exception ex) {
        log.warn("recordatorio de cargo de servicio: no se pudo avisar sobre customerPayment {}: {}",
            payment.getId(), ex.getMessage());
      }
    }
    notifyUnpaidToOps();
    return reminded;
  }

  /**
   * Cargos SERVICE_FEE que siguen PENDING pasados 7 días: un aviso a ops por
   * cargo (idempotente por evento de timeline, lo garantiza
   * TelegramNotifyService.shouldNotify). Nunca toca el cargo ni al cliente.
   */
  void notifyUnpaidToOps() {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(UNPAID_NOTIFY_AFTER_DAYS);
    for (CustomerPayment payment : customerPaymentRepository.findByStatusOrderByCreatedAtDesc(CustomerPaymentStatus.PENDING)) {
      if (payment.getKind() != CustomerPaymentKind.SERVICE_FEE || payment.getLeadId() == null) {
        continue;
      }
      if (payment.getCreatedAt() == null || payment.getCreatedAt().isAfter(cutoff)) {
        continue;
      }
      Lead lead = leadRepository.findById(payment.getLeadId()).orElse(null);
      if (lead == null) {
        continue;
      }
      try {
        telegramNotifyService.notifyServiceFeeUnpaid(lead, payment.getAmount(), payment.getId(), UNPAID_NOTIFY_AFTER_DAYS);
      } catch (Exception ex) {
        log.warn("aviso de cargo sin pagar a ops falló para customerPayment {}: {}", payment.getId(), ex.getMessage());
      }
    }
  }
}
