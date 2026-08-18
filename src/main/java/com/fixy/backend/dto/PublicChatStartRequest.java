package com.fixy.backend.dto;

/**
 * Request opcional al iniciar una conversacion chat-first.
 * Todos los campos son opcionales — el agente extrae lo que vaya
 * apareciendo a lo largo de la conversacion.
 *
 * <p>{@code sourceOfferId} (CTA "Pedir por Fixy",
 * FIXY_OFERTAS_CTA_DESIGN.md §3.2): opcional, viene del handoff
 * {@code /oferta/:id} → chat cuando el cliente arranca desde el botón de
 * una oferta con proveedor real. Se persiste tal cual en
 * {@code Lead.sourceOfferId} — no se valida contra el catálogo de ofertas
 * acá, es solo un dato de atribución para medir conversión.
 */
public record PublicChatStartRequest(
    String name,
    String phone,
    String channel,
    Long sourceOfferId
) {
}
