package com.fixy.backend.dto;

/** Edición parcial (contrato §2): solo se aplican los campos no-null. */
public record ServiceCatalogItemUpdateRequest(
    String category,
    String name,
    String description,
    Integer priceFrom,
    Integer priceTo,
    Integer durationMin,
    Boolean active,
    Integer sortOrder
) {
}
