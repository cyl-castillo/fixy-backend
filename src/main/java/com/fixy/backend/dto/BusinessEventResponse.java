package com.fixy.backend.dto;

import java.time.OffsetDateTime;

public record BusinessEventResponse(
    Long id,
    String type,
    String actor,
    String message,
    OffsetDateTime createdAt
) {
}
