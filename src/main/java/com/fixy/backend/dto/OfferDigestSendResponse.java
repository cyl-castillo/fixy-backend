package com.fixy.backend.dto;

/**
 * Respuesta de {@code POST /api/offers/digest/send}: conteo de qué pasó con
 * cada suscripción de cliente evaluada, mismas reglas que el preview.
 */
public record OfferDigestSendResponse(
    int sent,
    int skippedRecent,
    int skippedFewOffers,
    int skippedNoZone
) {
}
