package com.fixy.backend.dto;

/**
 * Body de {@code PATCH /api/public/inquiries/{id}?token=} (Fase 2, hueco de
 * contrato): el vecino activó las notificaciones DESPUÉS de crear su
 * consulta (el POST original quedó sin {@code pushEndpoint}) — esto lo
 * adjunta mientras la consulta siga {@code ESCALATED}.
 */
public record BusinessInquiryPushEndpointUpdateRequest(String pushEndpoint) {
}
