package com.fixy.backend.dto;

import java.util.List;

public record LeadMatchResponse(
    LeadResponse lead,
    List<ProviderMatchItem> matches,
    List<String> blockingFields,
    String nextRecommendedAction
) {
}
