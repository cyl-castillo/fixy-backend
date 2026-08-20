package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/**
 * Body del alta pública de ofertas (fase 2 del roadmap "ofertas
 * protagonistas"): el comerciante la carga solo desde su celular, sin pasar
 * por el admin de ops. {@code website} es el honeypot — mismo contrato que
 * {@link OfferInquiryCreateRequest}: un bot que lo completa recibe 201 igual
 * (nunca delatarlo) pero no persiste nada (ver
 * {@code PublicOfferSubmissionService.create}).
 *
 * <p>{@code latitude}/{@code longitude} son opcionales (fase 3 del pin en
 * mapa): hoy solo los manda el navegador si el comerciante autoriza
 * geolocalización.
 */
public record PublicOfferSubmissionRequest(
    String businessName,
    String whatsappNumber,
    String category,
    String zone,
    String address,
    Double latitude,
    Double longitude,
    String title,
    String discountText,
    String description,
    OffsetDateTime validUntil,
    String website
) {
}
