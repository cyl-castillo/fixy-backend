package com.fixy.backend.dto;

import com.fixy.backend.model.OfferStatus;
import java.time.OffsetDateTime;

/**
 * Oferta tal como la ve el DUEÑO en su panel self-service (Fase 5,
 * {@code MerchantPanelService}). A diferencia del DTO público
 * ({@code OfferPublicResponse}), acá viajan TODAS las ofertas del comercio
 * (cualquier {@link OfferStatus}, no solo {@code ACTIVE}) y las métricas
 * SIEMPRE reales — {@code viewCount} nunca se esconde bajo el umbral de
 * social proof: ese umbral es una política de cara al vecino, no al dueño
 * que la publicó.
 */
public record MerchantOfferSummary(
    Long id,
    String title,
    String discountText,
    OfferStatus status,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil,
    String photoUrl,
    int viewCount,
    int clickCount,
    int likeCount,
    int inquiryCount,
    int leadCount
) {
}
