package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** {@code PUT /api/businesses/{id}/catalog/{itemId}} — reemplazo completo
 * del ítem (no PATCH parcial): todos los campos de negocio son obligatorios
 * salvo {@code priceFrom}/{@code notes}. */
public record BusinessCatalogItemUpdateRequest(
    @NotBlank(message = "label is required") String label,
    @NotBlank(message = "kind is required") String kind,
    Integer priceFrom,
    @NotBlank(message = "confidence is required") String confidence,
    String notes,
    @NotNull(message = "active is required") Boolean active
) {
}
