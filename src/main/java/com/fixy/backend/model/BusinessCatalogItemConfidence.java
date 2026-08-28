package com.fixy.backend.model;

/**
 * Nivel de confianza de un ítem del catálogo (Fase 1, V24) — el campo clave
 * del plan de mutación hacia ficha estructurada: {@code DECLARADO} es lo que
 * ops o el dueño cargó a mano sin confirmación externa, {@code CONFIRMADO}
 * es lo que ya se validó con una consulta real resuelta (estampa {@code
 * verifiedAt}, ver {@code BusinessCatalogItemService}), {@code INFERIDO} es
 * lo que Fixy dedujo (ej. del rubro general) sin que nadie lo haya
 * confirmado todavía.
 */
public enum BusinessCatalogItemConfidence {
  DECLARADO,
  CONFIRMADO,
  INFERIDO
}
