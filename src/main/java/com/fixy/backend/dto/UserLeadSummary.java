package com.fixy.backend.dto;

import com.fixy.backend.model.LeadStatus;
import java.time.OffsetDateTime;

public record UserLeadSummary(
    Long id,
    String serviceCategory,
    String zone,
    LeadStatus status,
    String urgency,
    OffsetDateTime createdAt,
    String summary,
    String accessToken,
    boolean disputed,
    OffsetDateTime disputeResolvedAt
) {
}
