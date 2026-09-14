package com.fixy.backend.dto;

import java.util.List;

/**
 * Catálogo público de servicios agrupado por categoría (contrato
 * REFUNDACION_FASE1_CONTRATO.md §2): {@code GET /api/public/catalog/services}.
 * Solo categorías activas ({@code fixy.orders.active-categories}) y
 * servicios {@code active=true}, en el orden de {@code sortOrder}.
 */
public record ServiceCatalogGroupResponse(
    String category,
    String categoryLabel,
    List<Item> services
) {
  public record Item(
      String code,
      String name,
      String description,
      Integer priceFrom,
      Integer priceTo,
      Integer durationMin
  ) {
  }
}
