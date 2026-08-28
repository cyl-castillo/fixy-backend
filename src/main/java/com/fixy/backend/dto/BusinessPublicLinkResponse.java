package com.fixy.backend.dto;

/** {@code POST /api/businesses/{id}/public-link} (Fase 3): link de la
 * página pública del comercio, patrón idéntico a {@link BusinessPanelLinkResponse}. */
public record BusinessPublicLinkResponse(String url) {
}
