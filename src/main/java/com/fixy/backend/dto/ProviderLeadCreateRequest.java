package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record ProviderLeadCreateRequest(
    String name,
    String phone,
    @NotBlank(message = "message is required") String message,
    String channel
) {
}
