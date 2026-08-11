package com.fixy.backend.dto;

import com.fixy.backend.model.OfferStatus;
import java.time.OffsetDateTime;

public record OfferResponse(
    Long id,
    Long businessId,
    String title,
    String category,
    String zone,
    String description,
    String discountText,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    String photoUrl,
    OfferStatus status,
    String origin,
    String sourceMessageRaw,
    int viewCount,
    int clickCount,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
