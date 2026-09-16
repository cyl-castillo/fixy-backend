package com.fixy.backend.model;

/**
 * Estado público de la propuesta de precio nuevo de un {@link Lead} (Tier 1,
 * contrato §B.1, protocolo "al llegar"). Derivado, no persistido como tal —
 * ver {@link #of(Lead)} y el comentario sobre {@code priceChangeRejectedAt}
 * en {@link Lead} para por qué hace falta esa columna extra.
 */
public enum PriceChangeStatus {
  PENDING,
  ACCEPTED,
  REJECTED;

  /** Null si nunca hubo una propuesta en este lead. */
  public static PriceChangeStatus of(Lead lead) {
    if (lead.getProposedAt() == null) {
      return null;
    }
    if (lead.getAgreedAt() != null) {
      return ACCEPTED;
    }
    if (lead.getPriceChangeRejectedAt() != null) {
      return REJECTED;
    }
    return PENDING;
  }
}
