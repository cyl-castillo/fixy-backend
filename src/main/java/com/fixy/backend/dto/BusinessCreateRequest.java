package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record BusinessCreateRequest(
    @NotBlank(message = "name is required") String name,
    @NotBlank(message = "whatsappNumber is required") String whatsappNumber,
    @NotBlank(message = "category is required") String category,
    String primaryZone,
    Long providerId
) {
}
