package com.fixy.backend.dto;

import java.util.List;

/**
 * {@code GET /api/public/catalog/registration} — las dos listas que necesita
 * la puerta única de registro: oficios de proveedor ({@code ServiceCategory},
 * sin "otro") y rubros de comercio ({@code BusinessCategory}, catálogo
 * completo incluyendo "otro").
 */
public record RegistrationCatalogResponse(
    List<RegistrationCategoryOption> providerCategories,
    List<RegistrationCategoryOption> businessCategories
) {
}
