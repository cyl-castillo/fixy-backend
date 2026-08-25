package com.fixy.backend.dto;

/**
 * Respuesta de {@code POST /api/businesses/{id}/panel-link} (Fase 5): la URL
 * completa del panel self-service del dueño. Genera el token si el comercio
 * todavía no tenía uno; si ya tenía, devuelve el mismo — nunca regenera solo
 * (ver {@code BusinessService.ensurePanelLink}).
 */
public record BusinessPanelLinkResponse(String url) {
}
