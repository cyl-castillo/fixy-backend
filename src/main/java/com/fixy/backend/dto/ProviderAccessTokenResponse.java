package com.fixy.backend.dto;

public record ProviderAccessTokenResponse(
    Long providerId,
    String name,
    String accessToken,
    String url
) {
}
