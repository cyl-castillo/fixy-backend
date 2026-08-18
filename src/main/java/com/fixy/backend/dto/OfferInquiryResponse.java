package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/**
 * DTO admin de una consulta de comercio (drill-down por oferta,
 * FIXY_OFERTAS_CTA_DESIGN.md §4.3). No hay DTO público — el endpoint
 * público de creación responde {@code {"ok": true}} sin exponer el id
 * interno, no hay ninguna razón para que el cliente lo sepa.
 */
public record OfferInquiryResponse(
    Long id,
    Long offerId,
    Long businessId,
    String name,
    String whatsappNumber,
    String message,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
