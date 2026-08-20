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
 *
 * <p>{@code ctaType} (FIXY_OFERTAS_CTA_DESIGN.md §2): ruteo determinista del
 * botón de acción, calculado siempre server-side en
 * {@code OfferService.toPublicResponse} — el cliente nunca lo deriva, solo
 * lee el string. Valores: {@code "provider"} (el comercio también es un
 * Provider real — CTA "Pedir por Fixy" hacia el chat), {@code "comercio"}
 * (comercio real sin vínculo a Provider — CTA "Consultar", mini-form),
 * {@code "none"} (comercio scrapeado de una fuente pública, sin
 * interlocutor real — sin CTA de contacto).
 *
 * <p>{@code createdAt} se agregó de forma aditiva para el motor de ranking
 * de conveniencia ({@code OfferRankingService}, fase 1): el orden de la
 * lista ya no es cronológico, así que el cliente necesita esta fecha si
 * quiere mostrar "publicada hace X" sin depender del orden del array.
 *
 * <p>{@code likeCount} e {@code inquiryCount} se agregaron de forma aditiva
 * en fase 3 (interacción real del barrio como señal de ranking, ver
 * {@code OfferRankingService}). A diferencia de {@code viewCount} viajan
 * siempre como {@code int} crudo, sin el gate de social proof — no hay
 * política equivalente definida para "me sirve" ni consultas todavía, se
 * agrega si hace falta más adelante.
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
    Integer viewCount,
    String ctaType,
    OffsetDateTime createdAt,
    int likeCount,
    int inquiryCount
) {
}
