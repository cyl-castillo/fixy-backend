package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record IntakeRequest(
    @NotBlank(message = "message is required") String message,
    String contactName,
    String phone,
    String channel
) {
}
