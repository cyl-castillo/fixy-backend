package com.fixy.backend.model;

/** Cómo se cerró una {@link ProviderOffer} (Tier 2, contrato §A.2). Null
 * mientras la oferta sigue abierta (ni aceptada ni rechazada ni vencida). */
public enum ProviderOfferResponse {
  ACCEPTED,
  DECLINED,
  TIMEOUT
}
