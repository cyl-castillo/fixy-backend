package com.fixy.backend.dto;

/**
 * Respuesta del alta pública de ofertas ({@code POST
 * /api/public/offer-submissions}). {@code status} siempre viaja
 * {@code "DRAFT"} — el comerciante nunca publica directo, la puerta pública
 * solo alimenta la cola de aprobación de ops (ver {@code Offer.prePersist} y
 * {@code OfferService.approve}).
 */
public record PublicOfferSubmissionResponse(
    Long offerId,
    Long businessId,
    String status
) {
}
