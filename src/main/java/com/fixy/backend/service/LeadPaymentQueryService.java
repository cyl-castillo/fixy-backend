package com.fixy.backend.service;

import com.fixy.backend.dto.LeadPaymentSummary;
import com.fixy.backend.dto.ProviderCommissionSummary;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.repository.LeadPaymentRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Consultas de solo lectura de comisiones para el panel ops (H1.5) y para
 * el panel self-service del proveedor (H_B).
 */
@Service
public class LeadPaymentQueryService {

  private final LeadPaymentRepository leadPaymentRepository;

  public LeadPaymentQueryService(LeadPaymentRepository leadPaymentRepository) {
    this.leadPaymentRepository = leadPaymentRepository;
  }

  public List<LeadPaymentSummary> list(CommissionStatus statusFilter) {
    var payments = statusFilter != null
        ? leadPaymentRepository.findByCommissionStatusOrderByCreatedAtDesc(statusFilter)
        : leadPaymentRepository.findAllByOrderByCreatedAtDesc();
    return payments.stream().map(LeadPaymentSummary::fromEntity).toList();
  }

  /**
   * Totales de comisión (pendiente vs pagada) de un proveedor puntual, para
   * que el panel self-service muestre "cuánto le debe a Fixy" sin exponer
   * el detalle fila por fila (eso es de ops). Si el proveedor no tiene
   * ningún LeadPayment (payments deshabilitado, o sin leads COMPLETED
   * todavía), devuelve {@link ProviderCommissionSummary#empty()} — nunca
   * null ni un error, el front distingue "sin comisiones" de una falla.
   */
  public ProviderCommissionSummary summaryFor(Long providerId) {
    List<LeadPayment> payments = leadPaymentRepository.findByProviderIdOrderByCreatedAtDesc(providerId);
    if (payments.isEmpty()) {
      return ProviderCommissionSummary.empty();
    }

    BigDecimal pending = BigDecimal.ZERO;
    int pendingCount = 0;
    BigDecimal paid = BigDecimal.ZERO;
    int paidCount = 0;
    String currency = payments.get(0).getCurrency();

    for (LeadPayment payment : payments) {
      if (payment.getCommissionStatus() == CommissionStatus.PAID) {
        paid = paid.add(payment.getCommissionAmount());
        paidCount++;
      } else if (payment.getCommissionStatus() == CommissionStatus.PENDING
          || payment.getCommissionStatus() == CommissionStatus.OVERDUE) {
        pending = pending.add(payment.getCommissionAmount());
        pendingCount++;
      }
      // WAIVED no suma a ninguno de los dos totales: Fixy decidió no cobrarla.
    }

    return new ProviderCommissionSummary(pending, pendingCount, paid, paidCount, currency);
  }
}
