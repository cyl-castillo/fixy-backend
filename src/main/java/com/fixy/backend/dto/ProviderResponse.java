package com.fixy.backend.dto;

import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.ProviderVerificationStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record ProviderResponse(
    Long id,
    String name,
    String phone,
    String whatsappNumber,
    String sourceName,
    String sourceType,
    String primaryZone,
    List<String> coverageZones,
    String city,
    String department,
    List<String> categories,
    String categoryNotes,
    ProviderStatus status,
    ProviderVerificationStatus verificationStatus,
    Double ratingAverage,
    Integer ratingCount,
    Integer internalScore,
    List<String> riskFlags,
    OffsetDateTime lastContactedAt,
    OffsetDateTime lastRespondedAt,
    Integer acceptedJobsCount,
    Integer rejectedJobsCount,
    Integer completedJobsCount,
    String notes,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
