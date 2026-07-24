package com.fixy.backend.service;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
  private final LeadRepository leadRepository;
  private final LeadTimelineService timelineService;
  private final LeadMessageService leadMessageService;
  private final MercadoPagoService mercadoPagoService;
  private final ProviderRepository providerRepository;
  private final PushNotificationService pushNotificationService;
  private final BigDecimal commissionRate;
  private final BigDecimal repeatCommissionRate;

  public CommissionService(
      LeadPaymentRepository leadPaymentRepository,
      LeadRepository leadRepository,
      LeadTimelineService timelineService,
      LeadMessageService leadMessageService,
      MercadoPagoService mercadoPagoService,
      ProviderRepository providerRepository,
      PushNotificationService pushNotificationService,
      @Value("${fixy.payments.commission-percent:10}") double commissionPercent,
      @Value("${fixy.payments.repeat-commission-percent:5}") double repeatCommissionPercent
  ) {
    this.leadPaymentRepository = leadPaymentRepository;
    this.leadRepository = leadRepository;
    this.timelineService = timelineService;
    this.leadMessageService = leadMessageService;
    this.mercadoPagoService = mercadoPagoService;
    this.providerRepository = providerRepository;
    this.pushNotificationService = pushNotificationService;
    // commission-percent=10 -> rate=0.10. Se persiste con 4 decimales para
    // admitir tasas no enteras (ej. 8.5%) sin perder precisión.
    this.commissionRate = BigDecimal.valueOf(commissionPercent)
        .divide(BigDecimal.valueOf(100), RATE_SCALE, RoundingMode.HALF_EVEN);
    this.repeatCommissionRate = BigDecimal.valueOf(repeatCommissionPercent)
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
    boolean repeatCustomer = isRepeatCustomer(lead, provider);
    BigDecimal appliedRate = repeatCustomer ? repeatCommissionRate : commissionRate;
    BigDecimal commission = calculateCommission(normalizedAmount, appliedRate);

    LeadPayment payment = new LeadPayment();
    payment.setLeadId(lead.getId());
    payment.setProviderId(provider.getId());
    payment.setAmountCharged(normalizedAmount);
    payment.setCommissionRate(appliedRate);
    payment.setCommissionAmount(commission);
    payment.setCommissionStatus(CommissionStatus.PENDING);
    payment = leadPaymentRepository.save(payment);

    timelineService.appendEvent(lead, "COMMISSION_CREATED", "system",
        "Comisión %s %s (%.2f%% de %s %s%s) generada para el proveedor".formatted(
            payment.getCurrency(), commission, appliedRate.multiply(BigDecimal.valueOf(100)),
            payment.getCurrency(), normalizedAmount,
            repeatCustomer ? ", tarifa de cliente recurrente" : ""));

    java.util.Optional<MercadoPagoService.PreferenceResult> preference = mercadoPagoService
        .createCommissionPreference(
            payment.getId(),
            String.valueOf(payment.getId()),
            commission,
            payment.getCurrency(),
            "Comisión Fixy - lead #" + lead.getId()
        );

    // La tarifa recurrente es parte de la promesa del modelo ("el cliente
    // que te trajimos es cada vez más tuyo"): decirla explícitamente en el
    // aviso al proveedor vale tanto como cobrarla más barata.
    String repeatNote = repeatCustomer
        ? " Como ya atendiste a este cliente por Fixy, aplicamos la tarifa recurrente del %.0f%% (en vez del %.0f%%)."
            .formatted(repeatCommissionRate.multiply(BigDecimal.valueOf(100)),
                commissionRate.multiply(BigDecimal.valueOf(100)))
        : "";
    String message;
    if (preference.isPresent()) {
      payment.setMpPreferenceId(preference.get().preferenceId());
      payment.setMpPaymentLink(preference.get().initPoint());
      payment = leadPaymentRepository.save(payment);
      message = "Trabajo marcado como completado. Tu comisión Fixy es de %s %s.%s Pagala acá: %s"
          .formatted(payment.getCurrency(), commission, repeatNote, preference.get().initPoint());
    } else {
      log.warn("commission created without MP link (MP disabled or failed): leadPaymentId={}", payment.getId());
      message = "Trabajo marcado como completado. Tu comisión Fixy es de %s %s.%s Te vamos a enviar el link de pago en breve."
          .formatted(payment.getCurrency(), commission, repeatNote);
    }

    // provider_only: es la comisión que el proveedor le debe a Fixy, info
    // financiera del proveedor que el cliente no debe ver en su chat
    // (hallazgo del primer cobro real: se posteaba en el chat compartido).
    leadMessageService.postFromOps(lead.getId(), "fixy", message, "provider_only");

    return payment;
  }

  /**
   * Regla 10→5 del modelo de monetización (estudio 2026-07): la dupla
   * cliente-proveedor paga la tarifa base solo en su primer trabajo; los
   * siguientes van a la tarifa recurrente. "Recurrente" = este proveedor ya
   * tiene una comisión SALDADA (PAID o WAIVED — condonada también cuenta:
   * el trabajo existió) sobre otro lead con el mismo teléfono de cliente.
   * PENDING/OVERDUE no cuentan: el descuento se gana con la primera
   * comisión efectivamente resuelta, no con solo marcar completado.
   */
  private boolean isRepeatCustomer(Lead lead, Provider provider) {
    String customerPhone = normalizePhone(lead.getPhone());
    if (customerPhone == null) {
      return false;
    }
    List<LeadPayment> priorSettled = leadPaymentRepository
        .findByProviderIdOrderByCreatedAtDesc(provider.getId()).stream()
        .filter(p -> !Objects.equals(p.getLeadId(), lead.getId()))
        .filter(p -> p.getCommissionStatus() == CommissionStatus.PAID
            || p.getCommissionStatus() == CommissionStatus.WAIVED)
        .toList();
    if (priorSettled.isEmpty()) {
      return false;
    }
    Set<Long> priorLeadIds = priorSettled.stream()
        .map(LeadPayment::getLeadId)
        .collect(java.util.stream.Collectors.toSet());
    return leadRepository.findAllById(priorLeadIds).stream()
        .anyMatch(prior -> customerPhone.equals(normalizePhone(prior.getPhone())));
  }

  /**
   * Los teléfonos llegan como los tipeó el cliente ("099 123 456",
   * "+598 99 123 456"...). Para comparar identidad de cliente alcanza con
   * los últimos 8 dígitos (el número nacional uruguayo sin el 0/+598).
   */
  private static String normalizePhone(String phone) {
    if (phone == null) {
      return null;
    }
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() < 8) {
      return digits.isEmpty() ? null : digits;
    }
    return digits.substring(digits.length() - 8);
  }

  /**
   * Reactivación instantánea (FIXY_COBRANZAS.md): llamar cuando un
   * {@link LeadPayment} que ESTABA OVERDUE acaba de pasar a PAID (webhook
   * de MP o marca manual de ops). Avisa al proveedor por push que ya puede
   * volver a recibir pedidos y deja constancia en el timeline del lead.
   * No-op si el lead ya no existe (no debería pasar en flujo normal).
   */
  public void notifySettled(LeadPayment payment, Lead lead) {
    if (lead != null) {
      timelineService.appendEvent(lead, "COMMISSION_SETTLED", "system",
          "Comisión %s %s saldada — matching reactivado".formatted(
              payment.getCurrency(), payment.getCommissionAmount()));
    }
    Provider provider = providerRepository.findById(payment.getProviderId()).orElse(null);
    if (provider == null) {
      return;
    }
    pushNotificationService.notifyProvider(
        provider.getId(),
        provider.getAccessToken(),
        "¡Comisión saldada!",
        "Ya estás recibiendo pedidos de nuevo 💪"
    );
  }
}
