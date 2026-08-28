package com.fixy.backend.dto;

/**
 * Body de {@code POST /api/public/businesses/{businessId}/inquiries} (Fase 2).
 * {@code website} es el honeypot, mismo patrón que {@code
 * OfferInquiryCreateRequest}: un bot que lo completa recibe éxito igual sin
 * persistir ni notificar (ver {@code BusinessInquiryService.create}).
 */
public record BusinessInquiryCreateRequest(
    String question,
    String visitorName,
    String visitorWhatsapp,
    String pushEndpoint,
    Long offerId,
    String website
) {
}
