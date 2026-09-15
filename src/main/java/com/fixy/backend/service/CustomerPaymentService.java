package com.fixy.backend.service;

import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadRating;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.RemoteCarePlan;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadPhotoRepository;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Cargo de servicio al cliente (Refundación de Fixy, fase 2, contrato
 * REFUNDACION_FASE2_CONTRATO.md §A): el vecino le paga a Fixy, por Mercado
 * Pago, un % de lo que cobró el técnico. Compra garantía Fixy de {@code
 * fixy.guarantee.days}, reseña verificada y recibo. Reusa el mismo patrón
 * idempotente que {@link CommissionService} (que hace lo mismo pero cobrando
 * al TÉCNICO, no al cliente) — no se duplica lógica de matching ni de
 * mensajería, solo la orquestación del cobro cambia de sujeto.
 */
@Service
public class CustomerPaymentService {

  private static final Logger log = LoggerFactory.getLogger(CustomerPaymentService.class);
  private static final int MONEY_SCALE = 2;

  /** Prefijo de external_reference para que el webhook de MP rutee acá en
   * vez de a {@code LeadPayment} (contrato §A.4.2). */
  public static final String EXTERNAL_REFERENCE_PREFIX = "customer:";

  private final CustomerPaymentRepository customerPaymentRepository;
  private final LeadRepository leadRepository;
  private final LeadRatingRepository leadRatingRepository;
  private final LeadPhotoRepository leadPhotoRepository;
  private final LeadTimelineService timelineService;
  private final LeadMessageService leadMessageService;
  private final MercadoPagoService mercadoPagoService;
  private final BigDecimal serviceFeePercent;
  private final boolean serviceFeeEnabled;
  private final long guaranteeDays;
  private final Clock clock;

  public CustomerPaymentService(
      CustomerPaymentRepository customerPaymentRepository,
      LeadRepository leadRepository,
      LeadRatingRepository leadRatingRepository,
      LeadPhotoRepository leadPhotoRepository,
      LeadTimelineService timelineService,
      LeadMessageService leadMessageService,
      MercadoPagoService mercadoPagoService,
      @Value("${fixy.orders.service-fee-percent:15}") double serviceFeePercent,
      @Value("${fixy.orders.service-fee-enabled:true}") boolean serviceFeeEnabled,
      @Value("${fixy.guarantee.days:30}") long guaranteeDays,
      Clock clock
  ) {
    this.customerPaymentRepository = customerPaymentRepository;
    this.leadRepository = leadRepository;
    this.leadRatingRepository = leadRatingRepository;
    this.leadPhotoRepository = leadPhotoRepository;
    this.timelineService = timelineService;
    this.leadMessageService = leadMessageService;
    this.mercadoPagoService = mercadoPagoService;
    this.serviceFeePercent = BigDecimal.valueOf(serviceFeePercent);
    this.serviceFeeEnabled = serviceFeeEnabled;
    this.guaranteeDays = guaranteeDays;
    this.clock = clock;
  }

  public boolean isServiceFeeEnabled() {
    return serviceFeeEnabled;
  }

  /**
   * Contrato §A.4.1-3: el proveedor marcó COMPLETED con {@code
   * amountCharged}. Calcula el cargo (redondeo HALF_UP a entero de pesos),
   * lo persiste PENDING, intenta el link de MP y avisa al cliente. Un monto
   * cuyo cargo redondea a 0 (ej. la visita preventiva de precio 0 del plan
   * Casa a distancia, contrato §B.3) no genera cargo — {@code
   * Optional.empty()}, sin mensaje ni timeline.
   */
  public Optional<CustomerPayment> createServiceFeeForCompletedLead(
      Lead lead, Provider provider, BigDecimal amountCharged
  ) {
    if (!serviceFeeEnabled) {
      return Optional.empty();
    }
    BigDecimal normalizedBase = amountCharged.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal fee = normalizedBase.multiply(serviceFeePercent)
        .divide(BigDecimal.valueOf(100), MONEY_SCALE, RoundingMode.HALF_UP)
        .setScale(0, RoundingMode.HALF_UP);
    if (fee.signum() <= 0) {
      log.info("service fee for lead {} rounds to 0 (amountCharged={}), no charge created",
          lead.getId(), normalizedBase);
      return Optional.empty();
    }

    CustomerPayment payment = new CustomerPayment();
    payment.setKind(CustomerPaymentKind.SERVICE_FEE);
    payment.setLeadId(lead.getId());
    payment.setRemoteCarePlanId(lead.getRemoteCarePlanId());
    payment.setCustomerName(lead.getName());
    payment.setCustomerPhone(lead.getPhone());
    payment.setBaseAmount(normalizedBase);
    payment.setFeePercent(serviceFeePercent);
    payment.setAmount(fee);
    payment.setStatus(CustomerPaymentStatus.PENDING);
    payment = customerPaymentRepository.save(payment);

    Optional<MercadoPagoService.PreferenceResult> preference = mercadoPagoService.createPreference(
        EXTERNAL_REFERENCE_PREFIX + payment.getId(),
        "Servicio Fixy - lead #" + lead.getId(),
        fee,
        payment.getCurrency(),
        "customerPayment=" + payment.getId()
    );

    String providerName = provider != null && hasText(provider.getName()) ? provider.getName() : "El técnico";
    String feeText = ServiceCatalogService.formatUyu(fee.intValueExact());
    String feePercentText = serviceFeePercent.stripTrailingZeros().toPlainString();
    String baseMessage;
    if (preference.isPresent()) {
      payment.setMpPreferenceId(preference.get().preferenceId());
      payment.setMpPaymentLink(preference.get().initPoint());
      payment = customerPaymentRepository.save(payment);
      baseMessage = ("%s marcó el trabajo como terminado. Para activar la garantía Fixy de %d días y dejar tu "
          + "reseña verificada, pagá el servicio Fixy: $%s (%s%% de lo que cobró el técnico). Lo del técnico ya "
          + "está arreglado entre ustedes; esto es lo único que va a Fixy. → %s")
          .formatted(providerName, guaranteeDays, feeText, feePercentText, preference.get().initPoint());
    } else {
      log.warn("service fee created without MP link (MP disabled or failed): customerPaymentId={}", payment.getId());
      baseMessage = ("%s marcó el trabajo como terminado. Para activar la garantía Fixy de %d días y dejar tu "
          + "reseña verificada, pagá el servicio Fixy: $%s (%s%% de lo que cobró el técnico). Lo del técnico ya "
          + "está arreglado entre ustedes; esto es lo único que va a Fixy. Te mandamos el link de pago por WhatsApp.")
          .formatted(providerName, guaranteeDays, feeText, feePercentText);
    }

    // §B.4: "Al completar, el mensaje al cliente (A.4.3) antepone" el conteo
    // de fotos — solo tiene sentido para trabajo remoto (el dueño no está,
    // las fotos SON la forma de ver el trabajo).
    long photoCount = lead.isRemote() ? leadPhotoRepository.countByLeadId(lead.getId()) : 0;
    String message = lead.isRemote()
        ? "Trabajo terminado. %d %s del antes y el después en tu chat. ".formatted(
            photoCount, photoCount == 1 ? "foto" : "fotos") + baseMessage
        : baseMessage;

    timelineService.appendEvent(lead, "SERVICE_FEE_CREATED", "system",
        "Cargo de servicio Fixy %s %s (%s%% de %s %s) generado para el cliente".formatted(
            payment.getCurrency(), fee, feePercentText, payment.getCurrency(), normalizedBase));
    leadMessageService.postFromOps(lead.getId(), "fixy", message);

    return Optional.of(payment);
  }

  /**
   * Contrato §A.4.4: webhook de MP confirmó el pago. Transición atómica
   * (mismo patrón idempotente que {@code LeadPaymentRepository}), timeline,
   * mensaje al cliente con la garantía activa y, si ya existe un {@link
   * LeadRating} para el lead, lo marca {@code verified}.
   *
   * @return true si esta invocación ganó la transición (webhook nuevo);
   *         false si ya estaba PAID (notificación repetida, no-op).
   */
  public boolean markPaid(CustomerPayment payment, String mpPaymentId) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    OffsetDateTime guaranteeUntil = payment.getKind() == CustomerPaymentKind.SERVICE_FEE
        ? now.plusDays(guaranteeDays) : null;

    int transitioned = customerPaymentRepository.markPaidIfNotAlready(
        payment.getId(), mpPaymentId, now, guaranteeUntil);
    if (transitioned == 0) {
      log.info("mercadopago webhook: CustomerPayment {} ya estaba PAID, notificación repetida ignorada",
          payment.getId());
      return false;
    }

    if (payment.getKind() != CustomerPaymentKind.SERVICE_FEE || payment.getLeadId() == null) {
      // PLAN_MONTHLY no tiene lead ni garantía puntual — nada más que hacer acá.
      return true;
    }

    Lead lead = leadRepository.findById(payment.getLeadId()).orElse(null);
    if (lead == null) {
      log.warn("mercadopago webhook: lead {} no encontrado para CustomerPayment {} (evento no emitido)",
          payment.getLeadId(), payment.getId());
      return true;
    }

    timelineService.appendEvent(lead, "SERVICE_FEE_PAID", "system",
        "Cargo de servicio Fixy %s %s pagado (MP payment %s)".formatted(
            payment.getCurrency(), payment.getAmount(), mpPaymentId));

    String guaranteeDate = guaranteeUntil.toLocalDate().toString();
    leadMessageService.postFromOps(lead.getId(), "fixy",
        "Recibimos tu pago. Garantía Fixy activa hasta el %s. Recibo Fixy #%d.".formatted(
            guaranteeDate, payment.getId()));

    // Si el rating llegó ANTES que el pago, nace sin verificar — se
    // verifica ahora que el pago llegó. Si el rating llega DESPUÉS, nace
    // verified=true directo (ver LeadClosingService.confirmWithRating).
    leadRatingRepository.findByLeadId(lead.getId()).ifPresent(rating -> {
      if (!rating.isVerified()) {
        rating.setVerified(true);
        leadRatingRepository.save(rating);
      }
    });

    return true;
  }

  /** Contrato §A.4.4/§LeadClosingService: ¿tiene este lead un cargo de
   * servicio ya PAGADO? Usado al crear el {@link LeadRating} para que nazca
   * verificado si el pago llegó primero. */
  public boolean hasServiceFeePaid(Long leadId) {
    return customerPaymentRepository.findByLeadId(leadId)
        .filter(p -> p.getKind() == CustomerPaymentKind.SERVICE_FEE)
        .map(p -> p.getStatus() == CustomerPaymentStatus.PAID)
        .orElse(false);
  }

  /**
   * Contrato §B: cuota mensual del plan Casa a distancia. Sin lead, sin
   * garantía — {@code guaranteeUntil} queda null incluso cuando se marca
   * PAID (ver {@link #markPaid}).
   */
  public CustomerPayment createPlanMonthlyCharge(RemoteCarePlan plan) {
    CustomerPayment payment = new CustomerPayment();
    payment.setKind(CustomerPaymentKind.PLAN_MONTHLY);
    payment.setRemoteCarePlanId(plan.getId());
    payment.setCustomerName(plan.getOwnerName());
    payment.setCustomerPhone(plan.getOwnerPhone());
    payment.setAmount(BigDecimal.valueOf(plan.getMonthlyPrice()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    payment.setStatus(CustomerPaymentStatus.PENDING);
    payment = customerPaymentRepository.save(payment);

    Optional<MercadoPagoService.PreferenceResult> preference = mercadoPagoService.createPreference(
        EXTERNAL_REFERENCE_PREFIX + payment.getId(),
        "Fixy Casa a distancia - plan #" + plan.getId(),
        payment.getAmount(),
        payment.getCurrency(),
        "customerPayment=" + payment.getId()
    );
    if (preference.isPresent()) {
      payment.setMpPreferenceId(preference.get().preferenceId());
      payment.setMpPaymentLink(preference.get().initPoint());
      payment = customerPaymentRepository.save(payment);
    } else {
      log.warn("plan monthly charge created without MP link (MP disabled or failed): customerPaymentId={}",
          payment.getId());
    }
    return payment;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
