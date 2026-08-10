package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record OfferCreateRequest(
    @NotNull(message = "businessId is required") Long businessId,
    @NotBlank(message = "title is required") String title,
    @NotBlank(message = "category is required") String category,
    String zone,
    String description,
    String discountText,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    String photoUrl,
    String origin,
    String sourceMessageRaw
) {
}
