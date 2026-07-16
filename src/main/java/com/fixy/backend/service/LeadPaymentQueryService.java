package com.fixy.backend.service;

import com.fixy.backend.dto.LeadPaymentSummary;
import com.fixy.backend.dto.ProviderCommissionSummary;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.repository.LeadPaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Consultas de solo lectura de comisiones para el panel ops (H1.5) y para
 * el panel self-service del proveedor (H_B).
 */
@Service
public class LeadPaymentQueryService {

  private final LeadPaymentRepository leadPaymentRepository;
  private final Clock clock;

  public LeadPaymentQueryService(LeadPaymentRepository leadPaymentRepository, Clock clock) {
    this.leadPaymentRepository = leadPaymentRepository;
    this.clock = clock;
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

    BigDecimal earningsThisMonth = BigDecimal.ZERO;
    int earningsThisMonthCount = 0;
    BigDecimal earningsTotal = BigDecimal.ZERO;
    int earningsTotalCount = 0;
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<ProviderCommissionSummary.PendingCommission> pendingItems = new java.util.ArrayList<>();

    for (LeadPayment payment : payments) {
      if (payment.getCommissionStatus() == CommissionStatus.PAID) {
        paid = paid.add(payment.getCommissionAmount());
        paidCount++;
      } else if (payment.getCommissionStatus() == CommissionStatus.PENDING
          || payment.getCommissionStatus() == CommissionStatus.OVERDUE) {
        pending = pending.add(payment.getCommissionAmount());
        pendingCount++;
        // Item con link de pago para el botón "Pagar" del panel (ver javadoc
        // de PendingCommission). mpPaymentLink puede ser null si MP estaba
        // apagado al crearla — el front muestra el item igual, sin botón.
        pendingItems.add(new ProviderCommissionSummary.PendingCommission(
            payment.getLeadId(),
            payment.getCommissionAmount(),
            payment.getCurrency(),
            payment.getMpPaymentLink(),
            payment.getCreatedAt()));
      }
      // WAIVED no suma a ninguno de los dos totales: Fixy decidió no cobrarla.

      // Ingresos propios (lo que el proveedor GANÓ, no lo que le debe a
      // Fixy): todo LeadPayment representa un lead COMPLETED con monto
      // cobrado real, sin importar el estado de la comisión.
      earningsTotal = earningsTotal.add(payment.getAmountCharged());
      earningsTotalCount++;
      if (isSameMonth(payment.getCreatedAt(), now)) {
        earningsThisMonth = earningsThisMonth.add(payment.getAmountCharged());
        earningsThisMonthCount++;
      }
    }

    return new ProviderCommissionSummary(
        pending, pendingCount, paid, paidCount, currency,
        earningsThisMonth, earningsThisMonthCount, earningsTotal, earningsTotalCount,
        List.copyOf(pendingItems));
  }

  private boolean isSameMonth(OffsetDateTime a, OffsetDateTime b) {
    return a.getYear() == b.getYear() && a.getMonth() == b.getMonth();
  }
}
