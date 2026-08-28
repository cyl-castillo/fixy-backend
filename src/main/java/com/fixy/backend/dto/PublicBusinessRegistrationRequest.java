package com.fixy.backend.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/public/businesses/register} — ver {@code
 * BusinessRegistrationService}. Mismo criterio que {@code
 * PublicProviderRegistrationController.RegisterRequest}: {@code @NotNull}
 * (no {@code @NotBlank}) porque el servicio hace su propio trim/blank-check
 * con mensajes de dominio en español.
 */
public record PublicBusinessRegistrationRequest(
    @NotNull String credential,
    @NotNull String name,
    @NotNull String whatsappNumber,
    @NotNull String category,
    @NotNull String zone,
    String address
) {
}
