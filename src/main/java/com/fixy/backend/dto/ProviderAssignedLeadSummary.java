package com.fixy.backend.dto;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import java.time.OffsetDateTime;

public record ProviderAssignedLeadSummary(
    Long id,
    String customerName,
    String customerPhone,
    String detectedCategory,
    String urgency,
    String location,
    String summary,
    String problem,
    String address,
    String details,
    LeadStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String accessToken
) {
  public static ProviderAssignedLeadSummary fromEntity(Lead lead) {
    return new ProviderAssignedLeadSummary(
        lead.getId(),
        lead.getName(),
        lead.getPhone(),
        lead.getDetectedCategory(),
        lead.getUrgency(),
        lead.getLocation(),
        lead.getSummary(),
        lead.getProblem(),
        null,
        null,
        lead.getStatus(),
        lead.getCreatedAt(),
        lead.getUpdatedAt(),
        lead.getAccessToken()
    );
  }
}
