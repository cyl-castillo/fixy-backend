package com.fixy.backend.dto;

import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Fila del panel ops "Cobros" (contrato §A.4.6). */
public record CustomerPaymentSummary(
    Long id,
    CustomerPaymentKind kind,
    Long leadId,
    Long remoteCarePlanId,
    String customerName,
    String customerPhone,
    BigDecimal baseAmount,
    BigDecimal feePercent,
    BigDecimal amount,
    String currency,
    CustomerPaymentStatus status,
    String mpPaymentLink,
    OffsetDateTime createdAt,
    OffsetDateTime paidAt,
    OffsetDateTime guaranteeUntil
) {
  public static CustomerPaymentSummary fromEntity(CustomerPayment payment) {
    return new CustomerPaymentSummary(
        payment.getId(),
        payment.getKind(),
        payment.getLeadId(),
        payment.getRemoteCarePlanId(),
        payment.getCustomerName(),
        payment.getCustomerPhone(),
        payment.getBaseAmount(),
        payment.getFeePercent(),
        payment.getAmount(),
        payment.getCurrency(),
        payment.getStatus(),
        payment.getMpPaymentLink(),
        payment.getCreatedAt(),
        payment.getPaidAt(),
        payment.getGuaranteeUntil()
    );
  }
}
