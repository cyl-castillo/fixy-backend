package com.fixy.backend.dto;

import com.fixy.backend.model.BusinessStatus;

public record BusinessUpdateRequest(
    String name,
    String whatsappNumber,
    String category,
    String primaryZone,
    BusinessStatus status,
    Long providerId,
    String address,
    Double latitude,
    Double longitude
) {
}
