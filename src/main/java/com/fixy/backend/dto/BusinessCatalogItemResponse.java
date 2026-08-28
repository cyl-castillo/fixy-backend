package com.fixy.backend.dto;

import java.time.OffsetDateTime;

public record BusinessCatalogItemResponse(
    Long id,
    Long businessId,
    String label,
    String kind,
    Integer priceFrom,
    String confidence,
    OffsetDateTime verifiedAt,
    String notes,
    boolean active,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
