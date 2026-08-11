package com.fixy.backend.dto;

import java.util.List;

/**
 * Resumen de una corrida de {@code POST /api/offers/ingest} (diseño: la
 * ingesta nunca publica sola, todo entra/actualiza en DRAFT).
 *
 * @param created            ofertas nuevas creadas en DRAFT.
 * @param refreshed          ofertas DRAFT existentes cuyos datos se actualizaron
 *                           (una oferta ya aprobada/ACTIVE nunca se pisa sola).
 * @param discarded          ofertas DRAFT que ya no vinieron de la fuente y se
 *                           marcaron REJECTED (limpieza automática de la cola).
 * @param stillActiveMissingFromSource ids de ofertas ACTIVE cuya fuente ya no las
 *                           lista — NO se tocan automáticamente, se reportan para
 *                           que ops decida.
 */
public record OfferIngestResponse(
    int created,
    int refreshed,
    int discarded,
    List<Long> stillActiveMissingFromSource
) {
}
