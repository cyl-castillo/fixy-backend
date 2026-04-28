package com.fixy.backend.dto;

import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.ProviderVerificationStatus;

public record ProviderUpdateRequest(
    String name,
    String phone,
    String whatsappNumber,
    String sourceName,
    String sourceType,
    String primaryZone,
    String coverageZones,
    String city,
    String department,
    String categories,
    String categoryNotes,
    ProviderStatus status,
    ProviderVerificationStatus verificationStatus,
    Double ratingAverage,
    Integer ratingCount,
    Integer internalScore,
    String riskFlags,
    Integer acceptedJobsCount,
    Integer rejectedJobsCount,
    Integer completedJobsCount,
    String notes
) {
}
