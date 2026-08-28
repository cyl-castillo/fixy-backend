package com.fixy.backend.dto;

/** Respuesta de {@code POST /api/public/merchant/{token}/inquiries/{inquiryId}/answer} (Fase 2). */
public record BusinessInquiryOwnerAnswerResponse(
    Long id,
    String status,
    String answer,
    String answerNote,
    Long catalogItemId
) {
}
