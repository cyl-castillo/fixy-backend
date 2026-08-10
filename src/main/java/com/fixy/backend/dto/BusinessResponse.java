package com.fixy.backend.dto;

import com.fixy.backend.model.BusinessStatus;
import java.time.OffsetDateTime;

public record BusinessResponse(
    Long id,
    String name,
    String whatsappNumber,
    String category,
    String primaryZone,
    BusinessStatus status,
    Long providerId,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
