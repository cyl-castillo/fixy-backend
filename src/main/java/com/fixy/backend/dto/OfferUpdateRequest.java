package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/**
 * Edición de contenido de una oferta. Deliberadamente SIN campo status: la
 * única forma de cambiar el estado es a través de las acciones explícitas
 * {@code OfferService.approve}/{@code reject} (draft→active/rejected) o del
 * scheduler de expiración (active→expired) — nunca un PATCH genérico, para
 * que la regla de vigencia default (§5 del diseño) tenga un único punto de
 * entrada posible.
 */
public record OfferUpdateRequest(
    String title,
    String category,
    String zone,
    String description,
    String discountText,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    String photoUrl,
    String sourceMessageRaw
) {
}
