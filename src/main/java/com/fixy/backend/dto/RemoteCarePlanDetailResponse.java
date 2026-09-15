package com.fixy.backend.dto;

import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.RemoteCarePlanStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/public/remote-care/plans/{id}?token=} (contrato §B.3) —
 * la página del dueño (`/mi-casa/:id/:token`).
 */
public record RemoteCarePlanDetailResponse(
    Long id,
    RemoteCarePlanStatus status,
    String ownerName,
    String zone,
    String address,
    OnSite onSite,
    int monthlyPrice,
    OffsetDateTime activatedAt,
    String nextVisitNote,
    List<Order> orders,
    List<Payment> payments
) {
  public record OnSite(String name, String phone) {
  }

  public record Order(
      Long leadId, String serviceName, LeadStatus status, OffsetDateTime createdAt,
      int photoCount, String accessToken
  ) {
  }

  public record Payment(
      Long id, CustomerPaymentKind kind, BigDecimal amount, CustomerPaymentStatus status,
      String paymentLink, OffsetDateTime paidAt
  ) {
  }
}
