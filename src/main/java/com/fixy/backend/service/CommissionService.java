package com.fixy.backend.service;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadPaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orquesta la comisión de Fixy cuando un proveedor marca un lead como
 * COMPLETED (P0-1 / H1.2 + H1.3): calcula el monto, persiste el
 * {@link LeadPayment}, genera el link de pago de Mercado Pago (si está
 * configurado) y avisa al proveedor por el chat del lead.
 */
@Service
public class CommissionService {

  private static final Logger log = LoggerFactory.getLogger(CommissionService.class);
  private static final int MONEY_SCALE = 2;
  private static final int RATE_SCALE = 4;

  private final LeadPaymentRepository leadPaymentRepository;
  private final LeadTimelineService timelineService;
  private final LeadMessageService leadMessageService;
  private final MercadoPagoService mercadoPagoService;
  private final BigDecimal commissionRate;

  public CommissionService(
      LeadPaymentRepository leadPaymentRepository,
      LeadTimelineService timelineService,
      LeadMessageService leadMessageService,
      MercadoPagoService mercadoPagoService,
      @Value("${fixy.payments.commission-percent:10}") double commissionPercent
  ) {
    this.leadPaymentRepository = leadPaymentRepository;
    this.timelineService = timelineService;
    this.leadMessageService = leadMessageService;
    this.mercadoPagoService = mercadoPagoService;
    // commission-percent=10 -> rate=0.10. Se persiste con 4 decimales para
    // admitir tasas no enteras (ej. 8.5%) sin perder precisión.
    this.commissionRate = BigDecimal.valueOf(commissionPercent)
        .divide(BigDecimal.valueOf(100), RATE_SCALE, RoundingMode.HALF_EVEN);
  }

  /**
   * Calcula la comisión (rate * amountCharged, redondeo half-even a 2
   * decimales — estándar contable, evita sesgo sistemático hacia arriba en
   * redondeos consecutivos) sobre el monto declarado.
   */
  public BigDecimal calculateCommission(BigDecimal amountCharged, BigDecimal rate) {
    return amountCharged.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
  }

  /**
   * Crea el LeadPayment para un lead recién marcado COMPLETED, intenta
   * generar el link de pago en Mercado Pago y notifica al proveedor. Si MP
   * no está configurado, el LeadPayment queda igual en PENDING sin link —
   * no rompe el flujo de completar el trabajo.
   */
  public LeadPayment createForCompletedLead(Lead lead, Provider provider, BigDecimal amountCharged) {
    BigDecimal normalizedAmount = amountCharged.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    BigDecimal commission = calculateCommission(normalizedAmount, commissionRate);

    LeadPayment payment = new LeadPayment();
    payment.setLeadId(lead.getId());
    payment.setProviderId(provider.getId());
    payment.setAmountCharged(normalizedAmount);
    payment.setCommissionRate(commissionRate);
    payment.setCommissionAmount(commission);
    payment.setCommissionStatus(CommissionStatus.PENDING);
    payment = leadPaymentRepository.save(payment);

    timelineService.appendEvent(lead, "COMMISSION_CREATED", "system",
        "Comisión %s %s (%.2f%% de %s %s) generada para el proveedor".formatted(
            payment.getCurrency(), commission, commissionRate.multiply(BigDecimal.valueOf(100)),
            payment.getCurrency(), normalizedAmount));

    java.util.Optional<MercadoPagoService.PreferenceResult> preference = mercadoPagoService
        .createCommissionPreference(
            payment.getId(),
            String.valueOf(payment.getId()),
            commission,
            payment.getCurrency(),
            "Comisión Fixy - lead #" + lead.getId()
        );

    String message;
    if (preference.isPresent()) {
      payment.setMpPreferenceId(preference.get().preferenceId());
      payment.setMpPaymentLink(preference.get().initPoint());
      payment = leadPaymentRepository.save(payment);
      message = "Trabajo marcado como completado. Tu comisión Fixy es de %s %s. Pagala acá: %s"
          .formatted(payment.getCurrency(), commission, preference.get().initPoint());
    } else {
      log.warn("commission created without MP link (MP disabled or failed): leadPaymentId={}", payment.getId());
      message = "Trabajo marcado como completado. Tu comisión Fixy es de %s %s. Te vamos a enviar el link de pago en breve."
          .formatted(payment.getCurrency(), commission);
    }

    leadMessageService.postFromOps(lead.getId(), "fixy", message);

    return payment;
  }
}
