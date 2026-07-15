package com.fixy.backend.dto;

import com.fixy.backend.model.LeadStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record LeadResponse(
    Long id,
    String name,
    String phone,
    String problem,
    String detectedCategory,
    String urgency,
    String location,
    String summary,
    List<String> missingFields,
    List<String> blockingFields,
    boolean readyForMatching,
    String nextRecommendedAction,
    String assignedProvider,
    String notes,
    String history,
    LeadStatus status,
    String suggestedReply,
    String agentSource,
    String accessToken,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    boolean disputed,
    OffsetDateTime disputeResolvedAt,
    String disputeResolutionNote
) {
}
