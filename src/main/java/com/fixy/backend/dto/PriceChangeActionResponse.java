package com.fixy.backend.dto;

import com.fixy.backend.model.PriceChangeStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Tier 1 (contrato §B.1): respuesta de
 * {@code POST /api/public/leads/{leadId}/price-change/accept} y
 * {@code /reject} — vista mínima, sin traer todo {@link LeadResponse}.
 */
public record PriceChangeActionResponse(
    Long leadId,
    PriceChangeStatus status,
    BigDecimal agreedAmount,
    OffsetDateTime agreedAt
) {
}
