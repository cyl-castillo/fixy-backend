package com.fixy.backend.dto;

/** Body de {@code POST /api/public/merchant/{token}/inquiries/{inquiryId}/answer} (Fase 2). */
public record BusinessInquiryOwnerAnswerRequest(
    String answer,
    Integer priceFrom,
    String note
) {
}
