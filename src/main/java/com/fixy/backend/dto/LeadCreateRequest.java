package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record LeadCreateRequest(
    String name,
    String phone,
    @NotBlank(message = "problem is required") String problem,
    String channel
) {
}
