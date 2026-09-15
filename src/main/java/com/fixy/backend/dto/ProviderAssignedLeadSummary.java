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
    String accessToken,
    /** Fase 2 (contrato B.5): el técnico tiene que saber que el dueño no está. */
    Boolean remote,
    OnSiteContact onSiteContact
) {
  public record OnSiteContact(String name, String phone) {}

  public static ProviderAssignedLeadSummary fromEntity(Lead lead) {
    boolean remote = lead.isRemote();
    OnSiteContact onSite = remote && (lead.getOnSiteContactName() != null || lead.getOnSiteContactPhone() != null)
        ? new OnSiteContact(lead.getOnSiteContactName(), lead.getOnSiteContactPhone())
        : null;
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
        lead.getAccessToken(),
        remote,
        onSite
    );
  }
}
