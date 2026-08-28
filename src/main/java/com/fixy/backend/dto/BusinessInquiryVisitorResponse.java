package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/**
 * Respuesta de {@code GET /api/public/inquiries/{id}?token=} (Fase 2) — 404
 * opaco si el token no matchea. {@code verifiedAt} es el del ítem del
 * catálogo vinculado (null si la consulta no tiene ítem asociado todavía,
 * ej. sigue {@code ESCALATED}) — la página /consulta lo usa para mostrar
 * "confirmado el {fecha}", mismo dato que ya muestra la ficha en /admin.
 */
public record BusinessInquiryVisitorResponse(
    Long id,
    String status,
    String question,
    String answer,
    String answerNote,
    Integer priceFrom,
    OffsetDateTime verifiedAt,
    String businessName
) {
}
