package com.fixy.backend.service;

import com.fixy.backend.model.CustomerPayment;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Contrato §A.4.5: recordatorio único del cargo de servicio. Corre cada
 * hora; sobre cargos SERVICE_FEE PENDING con link, creados hace más de 48h
 * y nunca recordados, manda un mensaje amable UNA sola vez. Nunca cambia el
 * status — el vecino no paga, no pasa nada (doctrina del contrato: "nunca
 * castigar por no pagar").
 */
@Service
public class CustomerPaymentReminderScheduler {

  private static final Logger log = LoggerFactory.getLogger(CustomerPaymentReminderScheduler.class);
  private static final long DUE_AFTER_HOURS = 48;

  private final CustomerPaymentQueryService customerPaymentQueryService;
  private final LeadMessageService leadMessageService;
  private final boolean enabled;
  private final Clock clock;

  public CustomerPaymentReminderScheduler(
      CustomerPaymentQueryService customerPaymentQueryService,
      LeadMessageService leadMessageService,
      @Value("${fixy.customer-payments.reminder.enabled:true}") boolean enabled,
      Clock clock
  ) {
    this.customerPaymentQueryService = customerPaymentQueryService;
    this.leadMessageService = leadMessageService;
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
        leadMessageService.postFromOps(payment.getLeadId(), "fixy",
            "¿Te acordás del servicio Fixy? La garantía se activa cuando lo pagás: %s"
                .formatted(payment.getMpPaymentLink()));
        customerPaymentQueryService.markReminded(payment.getId());
        reminded++;
      } catch (Exception ex) {
        log.warn("recordatorio de cargo de servicio: no se pudo avisar sobre customerPayment {}: {}",
            payment.getId(), ex.getMessage());
      }
    }
    return reminded;
  }
}
