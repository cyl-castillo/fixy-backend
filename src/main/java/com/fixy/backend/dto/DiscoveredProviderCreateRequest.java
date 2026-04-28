package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record DiscoveredProviderCreateRequest(
    @NotBlank(message = "name is required") String name,
    @NotBlank(message = "phone is required") String phone,
    String whatsappNumber,
    String sourceName,
    String primaryZone,
    String coverageZones,
    String city,
    String department,
    String categories,
    String categoryNotes,
    String notes,
    boolean assignToLead
) {
}
