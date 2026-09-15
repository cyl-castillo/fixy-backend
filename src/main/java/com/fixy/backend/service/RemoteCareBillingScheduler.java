package com.fixy.backend.service;

import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.RemoteCarePlan;
import com.fixy.backend.model.RemoteCarePlanStatus;
import com.fixy.backend.repository.RemoteCarePlanRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Contrato §B.3: facturación mensual del plan Casa a distancia. Diario;
 * planes {@code ACTIVE} con {@code last_billed_at} vencido hace 30 días o
 * más generan una nueva cuota + link + aviso a ops por Telegram. Sin
 * castigo por atraso — ops lo ve y decide (doctrina del contrato: "nunca
 * castigar por no pagar").
 */
@Service
public class RemoteCareBillingScheduler {

  private static final Logger log = LoggerFactory.getLogger(RemoteCareBillingScheduler.class);
  private static final long BILLING_PERIOD_DAYS = 30;

  private final RemoteCarePlanRepository remoteCarePlanRepository;
  private final CustomerPaymentService customerPaymentService;
  private final TelegramNotifyService telegramNotifyService;
  private final WhatsAppService whatsAppService;
  private final boolean enabled;
  private final Clock clock;

  public RemoteCareBillingScheduler(
      RemoteCarePlanRepository remoteCarePlanRepository,
      CustomerPaymentService customerPaymentService,
      TelegramNotifyService telegramNotifyService,
      WhatsAppService whatsAppService,
      @Value("${fixy.remote-care.billing.enabled:true}") boolean enabled,
      Clock clock
  ) {
    this.remoteCarePlanRepository = remoteCarePlanRepository;
    this.customerPaymentService = customerPaymentService;
    this.telegramNotifyService = telegramNotifyService;
    this.whatsAppService = whatsAppService;
    this.enabled = enabled;
    this.clock = clock;
  }

  // Contrato §B.3: "diario" — default 24h, no cada hora como los otros
  // schedulers (facturar una vez al día alcanza de sobra para un ciclo de 30
  // días y evita corridas redundantes).
  @Scheduled(fixedDelayString = "${fixy.remote-care.billing.scheduler-fixed-delay-ms:86400000}")
  public void run() {
    if (!enabled) {
      return;
    }
    int billed = processOnce();
    if (billed > 0) {
      log.info("facturación casa a distancia: {} plan(es) facturados", billed);
    }
  }

  /** Un ciclo del job, invocable desde tests. Devuelve cuántos planes se facturaron. */
  public int processOnce() {
    OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(BILLING_PERIOD_DAYS);
    List<RemoteCarePlan> due = remoteCarePlanRepository
        .findByStatusAndLastBilledAtLessThanEqual(RemoteCarePlanStatus.ACTIVE, cutoff);

    int billed = 0;
    for (RemoteCarePlan plan : due) {
      try {
        CustomerPayment charge = customerPaymentService.createPlanMonthlyCharge(plan);
        plan.setLastBilledAt(OffsetDateTime.now(clock));
        remoteCarePlanRepository.save(plan);
        telegramNotifyService.notifyRemoteCarePlanCharge(plan, charge.getMpPaymentLink());
        if (whatsAppService.isEnabled() && charge.getMpPaymentLink() != null) {
          try {
            whatsAppService.sendText(plan.getOwnerPhone(),
                "Fixy Casa a distancia: cuota mensual de $%d lista. Pagá acá: %s"
                    .formatted(plan.getMonthlyPrice(), charge.getMpPaymentLink()));
          } catch (Exception ex) {
            log.warn("facturación casa a distancia: WhatsApp al dueño del plan {} falló: {}",
                plan.getId(), ex.getMessage());
          }
        }
        billed++;
      } catch (Exception ex) {
        log.warn("facturación casa a distancia: no se pudo facturar el plan {}: {}", plan.getId(), ex.getMessage());
      }
    }
    return billed;
  }
}
