package com.fixy.backend.dto;

public record BusinessHourResponse(
    Long id,
    Long businessId,
    int dayOfWeek,
    String opensAt,
    String closesAt,
    String note
) {
}
