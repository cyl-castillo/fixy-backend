package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;

/**
 * Una oferta scrapeada de una fuente pública (ver OfferService.ingest).
 * {@code businessName} resuelve (find-or-create) el {@code Business} al que
 * se adjunta la oferta — el scraper NO conoce ids internos de Fixy, solo el
 * nombre curado del comercio en su mapeo local ({@code merchants.yaml}).
 */
public record OfferIngestItem(
    @NotBlank(message = "externalKey is required") String externalKey,
    @NotBlank(message = "sourceName is required") String sourceName,
    String sourceUrl,
    @NotBlank(message = "businessName is required") String businessName,
    String businessCategory,
    @NotBlank(message = "title is required") String title,
    @NotBlank(message = "category is required") String category,
    String zone,
    boolean allZones,
    String description,
    String discountText,
    OffsetDateTime validFrom,
    OffsetDateTime validUntil
) {
}
