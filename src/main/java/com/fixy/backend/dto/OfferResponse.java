package com.fixy.backend.dto;

import com.fixy.backend.model.OfferStatus;
import java.time.OffsetDateTime;

public record OfferResponse(
    Long id,
    Long businessId,
    String title,
    String category,
    String zone,
    boolean allZones,
    String description,
    String discountText,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    String photoUrl,
    OfferStatus status,
    String origin,
    String sourceMessageRaw,
    String sourceName,
    String sourceUrl,
    String externalKey,
    int viewCount,
    int clickCount,
    // CTA de ofertas (FIXY_OFERTAS_CTA_DESIGN.md §3.2/§4.3): calculados por
    // query, no columnas incrementadas — leadCount refleja Lead.sourceOfferId
    // (una relación real, se desincronizaría si se contara con un contador
    // fire-and-forget) e inquiryCount cuenta OfferInquiry por offerId. El
    // "plus" medible del pitch a comercios: vistas + consultas reales.
    int leadCount,
    int inquiryCount,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
