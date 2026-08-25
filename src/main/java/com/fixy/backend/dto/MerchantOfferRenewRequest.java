package com.fixy.backend.dto;

/**
 * Body de {@code POST /api/public/merchant/{token}/offers/{offerId}/renew}
 * (Fase 5). {@code weeks} debe ser 1, 2 o 4 — validado en
 * {@code MerchantPanelService.renew} (no con Bean Validation, mismo criterio
 * manual que el resto de las validaciones de negocio del repo).
 */
public record MerchantOfferRenewRequest(Integer weeks) {
}
