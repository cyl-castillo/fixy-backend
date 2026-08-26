package com.fixy.backend.dto;

import java.util.List;

/**
 * {@code GET /api/public/merchant/{token}} (Fase 5): todo lo que el dueño ve
 * en su panel. {@code offers} incluye TODOS los estados (ACTIVE primero,
 * luego DRAFT, EXPIRED, REJECTED; dentro de cada grupo, {@code validUntil}
 * descendente — ver {@code MerchantPanelService}). {@code pendingInquiries}
 * (Fase 2, motor de respuesta) son las consultas ESCALATED del comercio,
 * más nuevas primero.
 */
public record MerchantPanelResponse(
    BusinessSummary business,
    List<MerchantOfferSummary> offers,
    List<BusinessInquiryPendingSummary> pendingInquiries
) {
  public record BusinessSummary(
      Long id,
      String name,
      String category,
      String primaryZone
  ) {
  }
}
