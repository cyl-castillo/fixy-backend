package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body de ops para resolver una disputa (cierre visible, Ola 2). */
public record DisputeResolutionRequest(
    @NotBlank @Size(max = 1000) String resolutionNote
) {
}
