package com.fixy.backend.dto;

public record DiscoveredProviderLinkResponse(
    ProviderResponse provider,
    LeadResponse lead,
    boolean assignedToLead,
    String message
) {
}
