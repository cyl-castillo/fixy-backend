package com.fixy.backend.dto;

import java.util.List;

/** {@code GET /.well-known/fixy-agent.json} (contrato §8). */
public record WellKnownAgentResponse(
    String name,
    String description,
    String website,
    String coverageArea,
    List<String> activeCategories,
    List<String> coverageZones,
    String mcpUrl,
    String catalogUrl,
    String whatsappContact
) {
}
