package com.fixy.backend.dto;

import java.util.List;

/** Resultado de {@code tools/call} (spec MCP 2025-06-18, sin SSE). */
public record McpToolResult(
    List<Content> content,
    boolean isError
) {
  public record Content(String type, String text) {
    public static Content text(String text) {
      return new Content("text", text);
    }
  }
}
