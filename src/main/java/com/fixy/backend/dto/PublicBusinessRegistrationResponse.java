package com.fixy.backend.dto;

/**
 * Respuesta de {@code POST /api/public/businesses/register}. {@code
 * alreadyExisted=true} cuando el sub de Google ya estaba vinculado a un
 * comercio (login implícito, no se creó nada nuevo).
 */
public record PublicBusinessRegistrationResponse(
    Long businessId, String name, String panelToken, boolean alreadyExisted
) {
}
