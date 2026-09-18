package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Tier 2 (contrato §C.3): {@code POST
 * /api/public/providers/{id}/leads/{leadId}/rating-reply}. */
public record LeadRatingReplyRequest(
    @NotBlank @Size(max = 500) String text
) {
}
