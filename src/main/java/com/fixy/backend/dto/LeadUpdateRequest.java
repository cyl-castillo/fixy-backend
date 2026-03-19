package com.fixy.backend.dto;

import com.fixy.backend.model.LeadStatus;

public record LeadUpdateRequest(
    LeadStatus status,
    String assignedProvider,
    String notes
) {
}
