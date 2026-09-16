package com.fixy.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Tier 1 (contrato §B.1): body de
 * {@code POST /api/public/providers/{providerId}/leads/{leadId}/price-change}.
 */
public record PriceChangeProposeRequest(
    @NotNull(message = "amount is required") @DecimalMin(value = "0.01", message = "amount debe ser mayor a 0") BigDecimal amount,
    @Size(max = 300, message = "reason no puede superar los 300 caracteres") String reason
) {
}
