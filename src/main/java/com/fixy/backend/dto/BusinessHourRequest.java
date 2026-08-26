package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Una franja dentro del array completo de {@code PUT
 * /api/businesses/{id}/hours} — ver BusinessHourService.replace. */
public record BusinessHourRequest(
    @NotNull(message = "dayOfWeek is required") Integer dayOfWeek,
    @NotBlank(message = "opensAt is required") String opensAt,
    @NotBlank(message = "closesAt is required") String closesAt,
    String note
) {
}
