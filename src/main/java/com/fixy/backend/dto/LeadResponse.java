package com.fixy.backend.dto;

import com.fixy.backend.model.LeadStatus;
import java.time.OffsetDateTime;

public record LeadResponse(
    Long id,
    String name,
    String phone,
    String problem,
    String detectedCategory,
    String urgency,
    String location,
    String assignedProvider,
    String notes,
    String history,
    LeadStatus status,
    String suggestedReply,
    String agentSource,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
