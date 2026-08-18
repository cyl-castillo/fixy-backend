package com.fixy.backend.dto;

/** {@code PATCH /api/offers/{id}/inquiries/{inquiryId}} — Carlos marca FORWARDED a mano tras reenviar por WhatsApp. */
public record OfferInquiryStatusUpdateRequest(String status) {
}
