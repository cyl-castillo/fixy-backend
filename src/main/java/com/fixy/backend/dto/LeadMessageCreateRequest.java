package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LeadMessageCreateRequest(
    @NotBlank @Size(max = 2000) String text
) {
}
