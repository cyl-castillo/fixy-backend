package com.fixy.backend.dto;

import java.util.List;

/** Respuesta de {@code GET /api/offers/digest/preview} — ver {@link OfferDigestZonePreview}. */
public record OfferDigestPreviewResponse(
    List<OfferDigestZonePreview> zones,
    int totalSubscribers,
    int totalToSend
) {
}
