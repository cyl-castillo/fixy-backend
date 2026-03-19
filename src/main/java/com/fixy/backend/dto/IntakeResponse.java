package com.fixy.backend.dto;

import java.util.List;

public record IntakeResponse(
    String leadType,
    String serviceCategory,
    String area,
    String urgency,
    String summary,
    List<String> missingFields,
    String suggestedReply,
    String agentSource
) {
}
