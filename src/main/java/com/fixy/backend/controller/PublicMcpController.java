package com.fixy.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fixy.backend.dto.McpToolResult;
import com.fixy.backend.service.McpToolService;
import com.fixy.backend.service.PublicLeadAbuseProtectionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Servidor MCP mínimo, stateless (Refundación de Fixy, fase 1, contrato §8):
 * "Streamable HTTP" de la spec MCP 2025-06-18 pero SOLO respuestas JSON, sin
 * SSE — cada request JSON-RPC 2.0 se contesta con un único JSON, sin estado
 * entre llamadas (no hay sesión: cada {@code initialize} es independiente,
 * como cualquier otro POST público de este backend).
 *
 * <p>Métodos: {@code initialize}, {@code notifications/initialized} (204,
 * es una notificación JSON-RPC — no lleva id ni espera respuesta con body),
 * {@code ping}, {@code tools/list}, {@code tools/call}. Las dos tools
 * ({@link McpToolService#LIST_SERVICES_TOOL}, {@link
 * McpToolService#CREATE_ORDER_TOOL}) reusan servicios existentes —
 * {@code McpToolService} no duplica lógica de matching ni de catálogo.
 */
@RestController
@RequestMapping("/api/public/mcp")
public class PublicMcpController {

  private static final String JSONRPC_VERSION = "2.0";
  private static final int ERROR_PARSE = -32700;
  private static final int ERROR_INVALID_REQUEST = -32600;
  private static final int ERROR_METHOD_NOT_FOUND = -32601;
  private static final int ERROR_INVALID_PARAMS = -32602;
  private static final int ERROR_INTERNAL = -32603;

  private final ObjectMapper objectMapper;
  private final McpToolService mcpToolService;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final String serverVersion;

  public PublicMcpController(
      ObjectMapper objectMapper,
      McpToolService mcpToolService,
      PublicLeadAbuseProtectionService abuseProtectionService
  ) {
    this.objectMapper = objectMapper;
    this.mcpToolService = mcpToolService;
    this.abuseProtectionService = abuseProtectionService;
    this.serverVersion = "1.0.0";
  }

  @PostMapping
  public ResponseEntity<ObjectNode> handle(@RequestBody(required = false) JsonNode body, HttpServletRequest httpRequest) {
    abuseProtectionService.validateMcp(httpRequest.getRemoteAddr());

    if (body == null || body.isMissingNode() || body.isNull()) {
      return ResponseEntity.ok(errorResponse(null, ERROR_INVALID_REQUEST, "empty request body"));
    }

    String method = body.path("method").asText(null);
    JsonNode id = body.has("id") ? body.get("id") : null;

    if (method == null || method.isBlank()) {
      return ResponseEntity.ok(errorResponse(id, ERROR_INVALID_REQUEST, "missing method"));
    }

    // Notificación JSON-RPC: sin body de respuesta (spec MCP + contrato §8).
    if ("notifications/initialized".equals(method)) {
      return ResponseEntity.noContent().build();
    }

    JsonNode params = body.path("params");

    try {
      return switch (method) {
        case "initialize" -> ResponseEntity.ok(successResponse(id, initializeResult()));
        case "ping" -> ResponseEntity.ok(successResponse(id, objectMapper.createObjectNode()));
        case "tools/list" -> ResponseEntity.ok(successResponse(id, toolsListResult()));
        case "tools/call" -> ResponseEntity.ok(toolsCallResponse(id, params, httpRequest.getRemoteAddr()));
        default -> ResponseEntity.ok(errorResponse(id, ERROR_METHOD_NOT_FOUND, "method not found: " + method));
      };
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.ok(errorResponse(id, ERROR_INVALID_PARAMS, ex.getMessage()));
    } catch (Exception ex) {
      return ResponseEntity.ok(errorResponse(id, ERROR_INTERNAL, "internal error"));
    }
  }

  private ObjectNode initializeResult() {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("protocolVersion", "2025-06-18");
    ObjectNode serverInfo = result.putObject("serverInfo");
    serverInfo.put("name", "fixy");
    serverInfo.put("version", serverVersion);
    result.putObject("capabilities").putObject("tools");
    return result;
  }

  private ObjectNode toolsListResult() {
    ObjectNode result = objectMapper.createObjectNode();
    var tools = result.putArray("tools");
    mcpToolService.definitions().forEach(def -> {
      ObjectNode tool = tools.addObject();
      tool.put("name", def.name());
      tool.put("description", def.description());
      tool.set("inputSchema", def.inputSchema());
    });
    return result;
  }

  private ObjectNode toolsCallResponse(JsonNode id, JsonNode params, String clientIp) {
    String toolName = params.path("name").asText(null);
    if (toolName == null || toolName.isBlank()) {
      return errorResponse(id, ERROR_INVALID_PARAMS, "missing tool name");
    }
    JsonNode arguments = params.path("arguments");

    McpToolResult result;
    try {
      result = mcpToolService.call(toolName, arguments.isMissingNode() ? null : arguments, clientIp);
    } catch (org.springframework.web.server.ResponseStatusException ex) {
      return errorResponse(id, ERROR_INVALID_PARAMS, ex.getReason());
    }

    ObjectNode resultNode = objectMapper.createObjectNode();
    var content = resultNode.putArray("content");
    result.content().forEach(item -> {
      ObjectNode contentNode = content.addObject();
      contentNode.put("type", item.type());
      contentNode.put("text", item.text());
    });
    resultNode.put("isError", result.isError());
    return successResponse(id, resultNode);
  }

  private ObjectNode successResponse(JsonNode id, JsonNode result) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", JSONRPC_VERSION);
    response.set("id", id == null ? objectMapper.nullNode() : id);
    response.set("result", result);
    return response;
  }

  private ObjectNode errorResponse(JsonNode id, int code, String message) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", JSONRPC_VERSION);
    response.set("id", id == null ? objectMapper.nullNode() : id);
    ObjectNode error = response.putObject("error");
    error.put("code", code);
    error.put("message", message == null ? "error" : message);
    return response;
  }
}
