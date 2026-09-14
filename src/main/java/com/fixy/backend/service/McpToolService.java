package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.McpToolDefinition;
import com.fixy.backend.dto.McpToolResult;
import com.fixy.backend.dto.OrderCreateRequest;
import com.fixy.backend.dto.OrderCreateResponse;
import com.fixy.backend.dto.ProviderPublicPreview;
import com.fixy.backend.dto.ServiceCatalogGroupResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Las dos tools que expone el servidor MCP (contrato §8, "Fixy llamable por
 * asistentes de IA"). Reusa {@link ServiceCatalogService}, {@link
 * ProviderCatalogService#publicPreview} y {@link OrderService} — misma
 * lógica que las superficies REST equivalentes, nada duplicado.
 */
@Service
public class McpToolService {

  public static final String LIST_SERVICES_TOOL = "fixy_list_services";
  public static final String CREATE_ORDER_TOOL = "fixy_create_order";

  private final ObjectMapper objectMapper;
  private final ServiceCatalogService serviceCatalogService;
  private final ProviderCatalogService providerCatalogService;
  private final OrderService orderService;
  private final Validator validator;
  private final String publicAppBaseUrl;
  private final JsonNode listServicesSchema;
  private final JsonNode createOrderSchema;

  public McpToolService(
      ObjectMapper objectMapper,
      ServiceCatalogService serviceCatalogService,
      ProviderCatalogService providerCatalogService,
      OrderService orderService,
      Validator validator,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl
  ) {
    this.objectMapper = objectMapper;
    this.serviceCatalogService = serviceCatalogService;
    this.providerCatalogService = providerCatalogService;
    this.orderService = orderService;
    this.validator = validator;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
    this.listServicesSchema = readSchema("""
        {
          "type": "object",
          "properties": {
            "category": {"type": "string", "description": "id de categoría, ej. plomeria (opcional: sin esto trae todas las categorías activas)"},
            "zone": {"type": "string", "description": "zona de cobertura, ej. Lagomar (opcional: junto con category suma cuántos técnicos hay disponibles ahí)"}
          }
        }
        """);
    this.createOrderSchema = readSchema("""
        {
          "type": "object",
          "required": ["serviceCode", "zone", "timeWindow", "name", "phone"],
          "properties": {
            "serviceCode": {"type": "string", "description": "code del servicio, ver fixy_list_services"},
            "zone": {"type": "string"},
            "timeWindow": {"type": "string", "enum": ["hoy", "manana_am", "manana_pm", "esta_semana", "coordinar"]},
            "name": {"type": "string"},
            "phone": {"type": "string"},
            "notes": {"type": "string"},
            "remote": {"type": "boolean", "description": "true si el cliente no va a estar en la casa"},
            "onSiteContact": {
              "type": "object",
              "properties": {
                "name": {"type": "string"},
                "phone": {"type": "string"}
              }
            },
            "channel": {"type": "string", "description": "opcional; si se omite queda como web-order, igual que el pedido estructurado del sitio"}
          }
        }
        """);
  }

  private JsonNode readSchema(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (Exception ex) {
      throw new IllegalStateException("invalid embedded MCP schema", ex);
    }
  }

  public List<McpToolDefinition> definitions() {
    return List.of(
        new McpToolDefinition(LIST_SERVICES_TOOL,
            "Catálogo de servicios de Fixy con precio cerrado, por categoría. Si se pasan category y zone juntos, también devuelve cuántos técnicos hay disponibles en esa zona.",
            listServicesSchema),
        new McpToolDefinition(CREATE_ORDER_TOOL,
            "Crea un pedido real en Fixy (mismo camino que el pedido estructurado del sitio: matching de técnico en el acto, aviso por WhatsApp). Devuelve el link de seguimiento del pedido.",
            createOrderSchema)
    );
  }

  public McpToolResult call(String toolName, JsonNode arguments, String clientIp) {
    if (LIST_SERVICES_TOOL.equals(toolName)) {
      return listServices(arguments);
    }
    if (CREATE_ORDER_TOOL.equals(toolName)) {
      return createOrder(arguments, clientIp);
    }
    throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "unknown tool: " + toolName);
  }

  private McpToolResult listServices(JsonNode arguments) {
    String category = textOrNull(arguments, "category");
    String zone = textOrNull(arguments, "zone");

    List<ServiceCatalogGroupResponse> catalog = serviceCatalogService.publicCatalog(category);

    StringBuilder text = new StringBuilder();
    if (catalog.isEmpty()) {
      text.append("No hay servicios activos para esa categoría.");
    } else {
      for (ServiceCatalogGroupResponse group : catalog) {
        text.append(group.categoryLabel()).append(":\n");
        for (ServiceCatalogGroupResponse.Item item : group.services()) {
          text.append("- ").append(item.name()).append(" — desde $").append(ServiceCatalogService.formatUyu(item.priceFrom()));
          if (item.priceTo() != null) {
            text.append(" a $").append(item.priceTo());
          }
          text.append(" (code: ").append(item.code()).append(")\n");
        }
      }
    }

    if (category != null && !category.isBlank() && zone != null && !zone.isBlank()) {
      ProviderPublicPreview preview = providerCatalogService.publicPreview(category, zone, 0);
      text.append("\nTécnicos disponibles ahora en ").append(zone).append(": ").append(preview.count());
    }

    return new McpToolResult(
        List.of(McpToolResult.Content.text(text.toString()), McpToolResult.Content.text(toJson(catalog))),
        false
    );
  }

  private McpToolResult createOrder(JsonNode arguments, String clientIp) {
    OrderCreateRequest request;
    try {
      request = objectMapper.treeToValue(arguments, OrderCreateRequest.class);
    } catch (Exception ex) {
      return new McpToolResult(List.of(McpToolResult.Content.text("Argumentos inválidos: " + ex.getMessage())), true);
    }

    // McpToolService llama a OrderService directo (sin pasar por @Valid del
    // controller REST) — mismas reglas (@NotBlank de OrderCreateRequest),
    // validadas acá a mano para no crear un lead con name/phone vacíos.
    Set<ConstraintViolation<OrderCreateRequest>> violations = validator.validate(request);
    if (!violations.isEmpty()) {
      String message = violations.stream()
          .map(v -> v.getPropertyPath() + ": " + v.getMessage())
          .collect(Collectors.joining("; "));
      return new McpToolResult(List.of(McpToolResult.Content.text("Argumentos inválidos: " + message)), true);
    }

    OrderCreateResponse response;
    try {
      // Misma ruta de servicio que POST /api/public/orders — nada duplicado:
      // validación, creación del lead, mensaje de confirmación y matching en
      // el acto corren exactamente igual, el asistente de IA es un canal más.
      response = orderService.create(request, clientIp);
    } catch (ResponseStatusException ex) {
      return new McpToolResult(List.of(McpToolResult.Content.text(ex.getReason())), true);
    }

    String trackingLink = "%s/c/%d/%s".formatted(publicAppBaseUrl, response.leadId(), response.accessToken());
    String text = "Pedido creado: %s, desde $%s. %s Seguimiento: %s".formatted(
        response.serviceName(),
        ServiceCatalogService.formatUyu(response.priceFrom()),
        "CONTACTING".equals(response.matchStatus())
            ? "Ya estamos contactando a un técnico."
            : "Por ahora no hay técnico libre, se avisa apenas aparezca uno.",
        trackingLink
    );

    return new McpToolResult(
        List.of(McpToolResult.Content.text(text), McpToolResult.Content.text(toJson(response))),
        false
    );
  }

  private String textOrNull(JsonNode arguments, String field) {
    if (arguments == null || !arguments.hasNonNull(field)) {
      return null;
    }
    String value = arguments.get(field).asText(null);
    return value == null || value.isBlank() ? null : value;
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception ex) {
      return "{}";
    }
  }
}
