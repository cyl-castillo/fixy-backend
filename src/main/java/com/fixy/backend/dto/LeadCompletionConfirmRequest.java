package com.fixy.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * H2.1: body de confirmación de completado del cliente.
 * {@code score} es obligatorio (1-5) solo si {@code confirmed=true}; la
 * validación de ese cruce vive en el servicio (no expresable con
 * anotaciones simples de Bean Validation sin un validator custom).
 */
public record LeadCompletionConfirmRequest(
    @NotNull Boolean confirmed,
    Integer score,
    @Size(max = 1000) String comment
) {
}
