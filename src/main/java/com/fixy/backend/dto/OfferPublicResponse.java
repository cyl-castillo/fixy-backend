package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/**
 * DTO público de una oferta vigente (superficie de cliente, Loop 2 del
 * roadmap — tarjeta de cierre de pedido, no el tab). Mínimo deliberado:
 * NUNCA {@code sourceMessageRaw} (auditoría interna) ni el
 * {@code whatsappNumber} del comercio — los perfiles públicos existentes de
 * proveedor ({@code ProviderPublicPreview}) tampoco exponen teléfono ni
 * datos personales, así que Ofertas sigue el mismo criterio ya sentado en
 * el repo. Sin dato de contacto, la tarjeta expandida en el cliente es
 * solo informativa (sin CTA de "escribir al comercio").
 */
public record OfferPublicResponse(
    Long id,
    String title,
    String discountText,
    String description,
    String category,
    String zone,
    String photoUrl,
    OffsetDateTime validUntil,
    String businessName
) {
}
