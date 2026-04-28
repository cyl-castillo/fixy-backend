package com.fixy.backend.dto;

public record PublicLeadContextUpdateRequest(
    String problem,
    String name,
    String phone,
    String channel,
    String location,
    String notes
) {
}
