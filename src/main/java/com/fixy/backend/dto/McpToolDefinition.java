package com.fixy.backend.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Definición de una tool MCP para {@code tools/list} (contrato §8). */
public record McpToolDefinition(
    String name,
    String description,
    JsonNode inputSchema
) {
}
