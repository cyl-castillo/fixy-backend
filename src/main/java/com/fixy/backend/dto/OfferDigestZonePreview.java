package com.fixy.backend.dto;

/**
 * Una fila del preview del digest semanal de ofertas por zona (Fase Push-1,
 * FIXY_OFERTAS_PUSH_Y_MAPA.md §3): cuántos suscriptores de cliente tiene
 * esa zona, cuántas ofertas vigentes le alcanzan, y si el envío pasaría las
 * reglas anti-spam duras ({@code OfferDigestService}). {@code zone ==
 * "sin zona"} es el bucket de suscripciones sin zona resuelta — siempre
 * {@code willSend = false}, nunca se les puede armar contenido.
 */
public record OfferDigestZonePreview(
    String zone,
    int subscribers,
    int activeOffers,
    boolean willSend,
    String reason
) {
}
