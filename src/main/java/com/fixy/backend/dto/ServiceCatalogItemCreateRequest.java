package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ServiceCatalogItemCreateRequest(
    @NotBlank(message = "category is required") String category,
    @NotBlank(message = "code is required") String code,
    @NotBlank(message = "name is required") String name,
    String description,
    @NotNull(message = "priceFrom is required") Integer priceFrom,
    Integer priceTo,
    Integer durationMin,
    Boolean active,
    Integer sortOrder
) {
}
