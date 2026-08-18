package com.fixy.backend.dto;

import java.util.List;

/**
 * Resumen de una corrida de {@code POST /api/offers/ingest} (diseño: la
 * ingesta nunca publica sola, nada de acá pasa a ACTIVE por sí solo).
 *
 * @param created            ofertas nuevas creadas en DRAFT.
 * @param refreshed          ofertas DRAFT existentes cuyos datos se actualizaron
 *                           (el contenido de una oferta ya aprobada nunca se pisa).
 * @param revalidated        ofertas ACTIVE a las que se les extendió la vigencia
 *                           porque la fuente las sigue publicando. Es lo ÚNICO que
 *                           se le toca a una oferta aprobada, y solo hacia adelante.
 * @param reopened           ofertas EXPIRED que la fuente sigue publicando y volvieron
 *                           a DRAFT para que ops las re-apruebe (antes quedaban muertas:
 *                           su externalKey ya existía y no se regeneraba el borrador).
 * @param discarded          ofertas DRAFT que ya no vinieron de la fuente y se
 *                           marcaron REJECTED (limpieza automática de la cola).
 * @param stillActiveMissingFromSource ids de ofertas ACTIVE cuya fuente ya no las
 *                           lista — NO se tocan automáticamente, se reportan para
 *                           que ops decida.
 */
public record OfferIngestResponse(
    int created,
    int refreshed,
    int revalidated,
    int reopened,
    int discarded,
    List<Long> stillActiveMissingFromSource
) {
}
