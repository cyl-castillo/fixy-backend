package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/**
 * DTO público de una oferta vigente (superficie de cliente, Loop 2 del
 * roadmap — tarjeta de cierre de pedido, no el tab). Mínimo deliberado:
 * NUNCA {@code sourceMessageRaw} (auditoría interna) ni {@code sourceUrl}
 * ni {@code externalKey} (detalle de scraping) ni el {@code whatsappNumber}
 * del comercio — los perfiles públicos existentes de proveedor
 * ({@code ProviderPublicPreview}) tampoco exponen teléfono ni datos
 * personales, así que Ofertas sigue el mismo criterio ya sentado en el
 * repo. Sin dato de contacto, la tarjeta expandida en el cliente es solo
 * informativa (sin CTA de "escribir al comercio").
 *
 * <p>{@code sourceName} SÍ se expone cuando existe (honestidad: si la
 * oferta viene de una ingesta automática de la web de un banco, el vecino
 * lo ve — "Fuente: Itaú beneficios" — en vez de que Fixy la presente como
 * propia).
 *
 * <p>{@code businessAddress} SÍ se expone: dirección física es dato
 * público (a diferencia de {@code whatsappNumber}), sin problema de
 * privacidad — puede ser null si el comercio no la cargó.
 *
 * <p>{@code viewCount} aplica la política de social proof "nunca mostrar
 * un número chico" en un solo lugar (el servidor): por debajo del umbral
 * configurable ({@code fixy.offers.social-proof-min-views}) viaja
 * {@code null} — nunca se le dice al vecino que una oferta "fue vista por
 * 2 vecinos".
 */
public record OfferPublicResponse(
    Long id,
    String title,
    String discountText,
    String description,
    String category,
    String zone,
    boolean allZones,
    String photoUrl,
    OffsetDateTime validUntil,
    String businessName,
    String sourceName,
    String businessAddress,
    Integer viewCount
) {
}
