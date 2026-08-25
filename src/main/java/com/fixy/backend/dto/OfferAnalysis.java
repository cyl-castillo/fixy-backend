package com.fixy.backend.dto;

/**
 * Análisis determinista "¿esta oferta conviene?" (fase 3 del roadmap de
 * ofertas — ver {@code OfferAnalysisService}). 100% calculado en código a
 * partir de datos ya existentes en la tabla {@code offers}: regla de oro del
 * proyecto, el LLM de prod ignora instrucciones, así que nada de esto se le
 * puede pedir por prompt.
 *
 * <p>Siempre presente como objeto en {@link OfferPublicResponse} (nunca
 * {@code null} el objeto entero) — sus tres campos individuales sí pueden
 * serlo, cada uno con su propio significado de ausencia documentado abajo.
 *
 * @param discountPercent porcentaje de descuento consolidado ({@code
 * DiscountParser.discountPercent}); {@code null} si el texto no es
 * comparable (monto fijo, "gratis" sin base, o sin {@code discountText}).
 * @param bestOfCategory {@code true} si esta oferta tiene el mayor {@code
 * discountPercent} entre las VIGENTES de su categoría (empate: gana la más
 * vieja por {@code createdAt}). Siempre {@code false} si {@code
 * discountPercent} es {@code null}, si es la única vigente de su categoría
 * (un "mejor de 1" no dice nada), o si ninguna oferta vigente de la
 * categoría tiene descuento comparable.
 * @param firstTimeInWeeks semanas completas (1 a 12) desde la última vez que
 * existió OTRA oferta de la misma categoría con {@code discountPercent}
 * igual o mejor que el de esta (historial completo, incluidas {@code
 * EXPIRED}). {@code null} si esta oferta no tiene {@code discountPercent},
 * si no hay ningún antecedente en el historial, o si el antecedente más
 * reciente es de hace menos de una semana.
 */
public record OfferAnalysis(
    Integer discountPercent,
    boolean bestOfCategory,
    Integer firstTimeInWeeks
) {
}
