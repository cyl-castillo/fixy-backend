package com.fixy.backend.dto;

public record LeadCompletionConfirmResponse(
    Long leadId,
    boolean confirmed,
    boolean disputed,
    Integer score
) {
}
