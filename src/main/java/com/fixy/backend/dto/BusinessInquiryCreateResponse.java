package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/**
 * Respuesta de {@code POST /api/public/businesses/{businessId}/inquiries}
 * (Fase 2): {@code status} es {@code ANSWERED_AUTO} o {@code ESCALATED}.
 * {@code accessToken} solo viaja si quedó escalada (el vecino lo necesita
 * para {@code GET /api/public/inquiries/{id}}); {@code answer} solo viaja
 * si el motor respondió solo.
 */
public record BusinessInquiryCreateResponse(
    String status,
    Long inquiryId,
    String accessToken,
    AnswerPayload answer
) {

  public record AnswerPayload(
      String value,
      String note,
      Integer priceFrom,
      OffsetDateTime verifiedAt,
      String businessName
  ) {
  }

  /** Honeypot (bot completó {@code website}): 201 igual, sin persistir ni
   * notificar a nadie — nunca delatar al bot (mismo criterio que {@code
   * OfferInquiryService.create}). */
  public static BusinessInquiryCreateResponse fakeOk() {
    return new BusinessInquiryCreateResponse("ANSWERED_AUTO", null, null, null);
  }
}
