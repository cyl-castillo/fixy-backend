package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Aviso push suelto de ops a un proveedor (2026-09-22): "cargá tu horario y
 * tu foto", "mañana pasamos a verte", etc. Abre el panel del proveedor al
 * tocarlo. No es un canal de conversación: es un empujón de una línea.
 */
public record ProviderNotifyRequest(
    @NotBlank @Size(max = 80) String title,
    @NotBlank @Size(max = 300) String body
) {
}
