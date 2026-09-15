package com.fixy.backend.service;

import com.fixy.backend.dto.CustomerPaymentSummary;
import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Consultas y mutaciones ops sobre {@link CustomerPayment} (contrato §A.4.6,
 * panel "Cobros"). Espejo de {@link LeadPaymentQueryService} para el cargo
 * al CLIENTE en vez de al técnico — mismo patrón idempotente
 * (markPaidIfNotAlready), sin la lógica de tarifa recurrente (no aplica acá).
 */
@Service
public class CustomerPaymentQueryService {

  /** Mismo criterio que {@code LeadPaymentQueryService}: prefijo distinguible
   * de un pago real confirmado por MP. */
  private static final String MANUAL_PAYMENT_PREFIX = "MANUAL:";
  private static final String WAIVED_PREFIX = "WAIVED:";

  private final CustomerPaymentRepository customerPaymentRepository;
  private final LeadRepository leadRepository;
  private final LeadTimelineService leadTimelineService;
  private final CustomerPaymentService customerPaymentService;
  private final Clock clock;

  public CustomerPaymentQueryService(
      CustomerPaymentRepository customerPaymentRepository,
      LeadRepository leadRepository,
      LeadTimelineService leadTimelineService,
      CustomerPaymentService customerPaymentService,
      Clock clock
  ) {
    this.customerPaymentRepository = customerPaymentRepository;
    this.leadRepository = leadRepository;
    this.leadTimelineService = leadTimelineService;
    this.customerPaymentService = customerPaymentService;
    this.clock = clock;
  }

  public List<CustomerPaymentSummary> list(CustomerPaymentStatus statusFilter) {
    var payments = statusFilter != null
        ? customerPaymentRepository.findByStatusOrderByCreatedAtDesc(statusFilter)
        : customerPaymentRepository.findAllByOrderByCreatedAtDesc();
    return payments.stream().map(CustomerPaymentSummary::fromEntity).toList();
  }

  List<CustomerPayment> findServiceFeeDueForReminder(OffsetDateTime cutoff) {
    return customerPaymentRepository.findServiceFeeDueForReminder(cutoff);
  }

  void markReminded(Long id) {
    customerPaymentRepository.findById(id).ifPresent(payment -> {
      payment.setRemindedAt(OffsetDateTime.now(clock));
      customerPaymentRepository.save(payment);
    });
  }

  /**
   * Panel admin: marca un cargo como cobrado por fuera de Mercado Pago
   * (efectivo, transferencia). Idempotente sobre uno ya PAID.
   */
  public CustomerPaymentSummary markPaidManually(Long id, String note) {
    CustomerPayment payment = customerPaymentRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "customer payment not found"));

    if (payment.getStatus() == CustomerPaymentStatus.PAID) {
      return CustomerPaymentSummary.fromEntity(payment);
    }

    String marker = MANUAL_PAYMENT_PREFIX + (note == null || note.isBlank() ? "manual" : note.trim());
    boolean transitioned = customerPaymentService.markPaid(payment, marker);
    if (!transitioned) {
      return CustomerPaymentSummary.fromEntity(customerPaymentRepository.findById(id).orElseThrow());
    }
    return CustomerPaymentSummary.fromEntity(customerPaymentRepository.findById(id).orElseThrow());
  }

  /**
   * Panel admin: condona un cargo (Fixy decide no cobrarlo). No permitido
   * sobre uno ya PAID (409). Idempotente si ya está WAIVED.
   */
  public CustomerPaymentSummary waiveManually(Long id, String note) {
    CustomerPayment payment = customerPaymentRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "customer payment not found"));

    if (payment.getStatus() == CustomerPaymentStatus.PAID) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "no se puede condonar un cargo ya cobrado");
    }
    if (payment.getStatus() == CustomerPaymentStatus.WAIVED) {
      return CustomerPaymentSummary.fromEntity(payment);
    }

    payment.setStatus(CustomerPaymentStatus.WAIVED);
    payment.setMpPaymentId(WAIVED_PREFIX + (note == null || note.isBlank() ? "condonado" : note.trim()));
    CustomerPayment saved = customerPaymentRepository.save(payment);

    if (saved.getLeadId() != null) {
      Optional<Lead> lead = leadRepository.findById(saved.getLeadId());
      lead.ifPresent(value -> leadTimelineService.appendEvent(value, "SERVICE_FEE_WAIVED", "ops",
          "Cargo de servicio Fixy %s %s condonado".formatted(saved.getCurrency(), saved.getAmount())));
    }

    return CustomerPaymentSummary.fromEntity(saved);
  }
}
