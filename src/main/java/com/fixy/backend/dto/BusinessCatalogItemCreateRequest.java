package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/businesses/{id}/catalog}. {@code kind}/{@code
 * confidence} viajan como texto (no el enum directo) para poder devolver un
 * 400 con mensaje claro en vez del error crudo de deserialización de
 * Jackson ante un valor inválido — mismo criterio que
 * OfferInquiryStatusUpdateRequest/OfferInquiryService. */
public record BusinessCatalogItemCreateRequest(
    @NotBlank(message = "label is required") String label,
    @NotBlank(message = "kind is required") String kind,
    Integer priceFrom,
    @NotBlank(message = "confidence is required") String confidence,
    String notes
) {
}
