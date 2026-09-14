package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/** Vista admin (flat) de un ítem del catálogo — {@code /api/services}. */
public record ServiceCatalogItemResponse(
    Long id,
    String category,
    String code,
    String name,
    String description,
    Integer priceFrom,
    Integer priceTo,
    Integer durationMin,
    boolean active,
    int sortOrder,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
