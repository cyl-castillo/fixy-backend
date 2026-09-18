package com.fixy.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Tier 2 (contrato §C.3): {@code POST /api/public/leads/{id}/rating}. */
public record LeadRatingSubmitRequest(
    @NotNull @Min(1) @Max(5) Integer score,
    @Size(max = 1000) String comment
) {
}
