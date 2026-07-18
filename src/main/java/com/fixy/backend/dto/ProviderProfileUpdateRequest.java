package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body de {@code PATCH /api/public/providers/{id}/profile}: SOLO campos que
 * el proveedor puede editar de sí mismo. Deliberadamente no incluye status,
 * rating ni categories — eso lo gestiona ops (categorías afectan matching,
 * ver {@link com.fixy.backend.service.ProviderCatalogService}).
 */
public record ProviderProfileUpdateRequest(
    @NotBlank(message = "el nombre no puede estar vacío")
    @Size(max = 120, message = "el nombre es demasiado largo")
    String name,

    @Size(max = 500, message = "la descripción es demasiado larga (máximo 500 caracteres)")
    String description,

    @Size(max = 1000, message = "las zonas de cobertura son demasiado largas")
    String coverageZones,

    @Size(max = 40, message = "el teléfono es demasiado largo")
    String phone
) {
}
