package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record ProviderCreateRequest(
    @NotBlank(message = "name is required") String name,
    @NotBlank(message = "phone is required") String phone,
    String whatsappNumber,
    String sourceName,
    String sourceType,
    String primaryZone,
    String coverageZones,
    String city,
    String department,
    @NotBlank(message = "categories is required") String categories,
    String categoryNotes,
    String notes
) {
}
