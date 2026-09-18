package com.fixy.backend.model;

/** Por qué se generó esta oferta (Tier 2, contrato §A.1/§A.2) — solo cambia
 * el copy que ve el cliente/la timeline, mismo criterio que
 * {@code LeadAgentService.MatchContext}, que esta clase reemplaza como
 * fuente persistida (antes solo vivía como enum interno sin fila propia). */
public enum ProviderOfferContext {
  INITIAL,
  SCHEDULED_RETRY,
  DECLINE_REOFFER
}
