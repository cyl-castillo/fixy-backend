package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/** Una fila de {@code MerchantPanelResponse.pendingInquiries} (Fase 2) — consultas ESCALATED del comercio. */
public record BusinessInquiryPendingSummary(
    Long id,
    String question,
    String visitorName,
    String visitorWhatsapp,
    Long offerId,
    OffsetDateTime createdAt
) {
}
