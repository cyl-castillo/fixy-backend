package com.fixy.backend.dto;

import com.fixy.backend.model.ProviderLeadStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record ProviderLeadResponse(
    Long id,
    String name,
    String phone,
    String message,
    String category,
    String zone,
    String urgency,
    String summary,
    List<String> missingFields,
    boolean readyForReview,
    String nextRecommendedAction,
    ProviderLeadStatus status,
    String suggestedReply,
    String agentSource,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
