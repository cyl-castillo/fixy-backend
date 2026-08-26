package com.fixy.backend.model;

/**
 * Naturaleza de un ítem del catálogo de la ficha (Fase 1, V24): distingue un
 * rubro genérico ({@code CATEGORY}, ej. "ferretería"), una marca que el
 * comercio vende ({@code BRAND}, ej. "Sherwin Williams") de un producto
 * puntual ({@code PRODUCT}, ej. "taladro Bosch GSB 13"). El motor de
 * respuesta sobre fichas (fase 2) usa esta distinción para decidir qué tan
 * literal tiene que ser el match contra la pregunta del vecino.
 */
public enum BusinessCatalogItemKind {
  CATEGORY,
  BRAND,
  PRODUCT
}
