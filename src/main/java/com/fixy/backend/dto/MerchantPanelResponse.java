package com.fixy.backend.dto;

import java.util.List;

/**
 * {@code GET /api/public/merchant/{token}} (Fase 5): todo lo que el dueño ve
 * en su panel. {@code offers} incluye TODOS los estados (ACTIVE primero,
 * luego DRAFT, EXPIRED, REJECTED; dentro de cada grupo, {@code validUntil}
 * descendente — ver {@code MerchantPanelService}).
 */
public record MerchantPanelResponse(
    BusinessSummary business,
    List<MerchantOfferSummary> offers
) {
  public record BusinessSummary(
      Long id,
      String name,
      String category,
      String primaryZone
  ) {
  }
}
