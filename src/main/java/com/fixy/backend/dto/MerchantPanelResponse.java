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
  /**
   * {@code publicUrl} se agregó de forma aditiva en Fase 3 (página pública
   * del comercio, gap analysis 2026-08-25 §8): a diferencia de {@code
   * OfferPublicResponse.businessSlug} (GET público anónimo, nunca genera
   * slug), ACÁ el GET del panel SÍ dispara {@code
   * BusinessSlugService.ensureSlug} — es un endpoint autenticado por token
   * (no un GET anónimo cualquiera) y de bajo tráfico (el dueño entra a su
   * propio panel), así que el dueño ve el link de su ficha pública desde el
   * primer ingreso sin tener que pedirlo aparte a ops.
   */
  public record BusinessSummary(
      Long id,
      String name,
      String category,
      String primaryZone,
      String publicUrl
  ) {
  }
}
