package com.fixy.backend.dto;

/**
 * Body del mini-form público de consulta (FIXY_OFERTAS_CTA_DESIGN.md §4.2/§6).
 * {@code website} es el honeypot: campo oculto en el form real, un bot que
 * lo completa se detecta acá — la request responde 201 igual (nunca delatar
 * al bot) pero no persiste ni notifica (ver
 * {@code OfferInquiryService.create}).
 */
public record OfferInquiryCreateRequest(
    String name,
    String whatsappNumber,
    String message,
    String website
) {
}
