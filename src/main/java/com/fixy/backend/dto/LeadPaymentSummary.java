package com.fixy.backend.dto;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.LeadPayment;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Fila del panel ops de comisiones (H1.5). leadId/providerId van "en crudo"
 * (sin resolver nombre) para mantener este DTO simple; el frontend de ops
 * ya tiene el catálogo de leads/proveedores cargado y puede cruzar por id.
 */
public record LeadPaymentSummary(
    Long id,
    Long leadId,
    Long providerId,
    BigDecimal amountCharged,
    BigDecimal commissionRate,
    BigDecimal commissionAmount,
    String currency,
    CommissionStatus commissionStatus,
    String mpPaymentLink,
    OffsetDateTime createdAt,
    OffsetDateTime paidAt
) {
  public static LeadPaymentSummary fromEntity(LeadPayment payment) {
    return new LeadPaymentSummary(
        payment.getId(),
        payment.getLeadId(),
        payment.getProviderId(),
        payment.getAmountCharged(),
        payment.getCommissionRate(),
        payment.getCommissionAmount(),
        payment.getCurrency(),
        payment.getCommissionStatus(),
        payment.getMpPaymentLink(),
        payment.getCreatedAt(),
        payment.getPaidAt()
    );
  }
}
