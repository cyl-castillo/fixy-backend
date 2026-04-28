package com.fixy.backend.dto;

import java.util.List;

public record ProviderMatchItem(
    Long providerId,
    String name,
    String category,
    String zone,
    String phone,
    int score,
    List<String> reasons,
    String status,
    String sourceType
) {
}
