package com.fixy.backend.model;

/**
 * Ciclo de vida de {@link Offer} (diseño §3.2). {@code REJECTED} es una
 * adición sobre lo que pedía el roadmap original (draft/activa/vencida):
 * hace falta un estado explícito para "ops revisó y descartó", distinto de
 * "todavía no revisado" ({@code DRAFT}) — sin él, un draft rechazado y uno
 * recién llegado son indistinguibles en la cola de aprobación.
 */
public enum OfferStatus {
  DRAFT,
  ACTIVE,
  EXPIRED,
  REJECTED
}
