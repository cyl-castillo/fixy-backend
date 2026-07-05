package com.fixy.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifica, sin llamar a la red, el contrato del clasificador de intake multi-proveedor
 * cuando fixy.agent.provider=workersai (la config real de producción):
 *  - el json_schema exigido por Cloudflare Workers AI en response_format tiene el shape correcto,
 *  - el parseo de result.response soporta tanto texto (caso normal) como objeto (algunos modelos
 *    devuelven el JSON ya parseado), igual que LeadAgentService.callWorkersAiJson,
 *  - classify() cae a heurística cuando el provider es workersai pero faltan credenciales CF,
 *  - classify() mantiene el comportamiento actual (heurística) cuando el provider es openai/otro
 *    y no hay api key.
 */
class AgentServiceWorkersAiPayloadTest {

  @Test
  void intakeJsonSchemaHasExpectedShapeAndFields() {
    Map<String, Object> schema = AgentService.intakeJsonSchema();

    assertEquals("object", schema.get("type"));
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    for (String field : List.of("leadType", "serviceCategory", "area", "urgency", "summary", "missingFields", "suggestedReply")) {
      assertTrue(properties.containsKey(field), "schema debe incluir el campo " + field);
    }
    @SuppressWarnings("unchecked")
    List<String> required = (List<String>) schema.get("required");
    assertEquals(7, required.size(), "las 7 claves del contrato de IntakeResponse deben ser required");

    @SuppressWarnings("unchecked")
    Map<String, Object> serviceCategory = (Map<String, Object>) properties.get("serviceCategory");
    @SuppressWarnings("unchecked")
    List<String> categoryEnum = (List<String>) serviceCategory.get("enum");
    assertTrue(categoryEnum.containsAll(List.of("plomeria", "electricidad", "cerrajeria", "barometrica",
        "jardineria", "aires_acondicionados", "reparaciones", "otro")),
        "el enum debe cubrir las categorías reales del dominio (golden set), no solo las 5 del prompt md");
  }

  @Test
  void parsesResponseAsTextJson() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String raw = """
        {"success": true, "result": {"response": "{\\"leadType\\":\\"cliente\\",\\"serviceCategory\\":\\"plomeria\\",\\"area\\":\\"Solymar\\",\\"urgency\\":\\"alta\\",\\"summary\\":\\"s\\",\\"missingFields\\":[],\\"suggestedReply\\":\\"r\\"}"}}
        """;

    JsonNode result = AgentService.parseWorkersAiResult(mapper, raw);

    assertEquals("cliente", result.path("leadType").asText());
    assertEquals("plomeria", result.path("serviceCategory").asText());
    assertEquals("Solymar", result.path("area").asText());
  }

  @Test
  void parsesResponseAsDirectObject() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String raw = """
        {"success": true, "result": {"response": {"leadType":"cliente","serviceCategory":"electricidad","area":"El Pinar","urgency":"alta","summary":"s","missingFields":[],"suggestedReply":"r"}}}
        """;

    JsonNode result = AgentService.parseWorkersAiResult(mapper, raw);

    assertEquals("electricidad", result.path("serviceCategory").asText());
    assertEquals("El Pinar", result.path("area").asText());
  }

  @Test
  void returnsNullWhenNonSuccess() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String raw = """
        {"success": false, "errors": [{"message": "model not found"}]}
        """;

    assertNull(AgentService.parseWorkersAiResult(mapper, raw));
  }

  @Test
  void returnsNullWhenResponseIsNullOrMissing() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String raw = """
        {"success": true, "result": {"response": null}}
        """;

    assertNull(AgentService.parseWorkersAiResult(mapper, raw));
  }

  @Test
  void returnsNullOnBlankOrNullRawBody() throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    assertNull(AgentService.parseWorkersAiResult(mapper, null));
    assertNull(AgentService.parseWorkersAiResult(mapper, ""));
    assertNull(AgentService.parseWorkersAiResult(mapper, "   "));
  }

  @Test
  void classifyFallsBackToHeuristicWhenWorkersaiProviderMissingCredentials() {
    // provider=workersai pero sin account-id/api-token -> no debe intentar red, cae directo
    // a heurística (mismo comportamiento que hoy con openai sin key).
    AgentService service = new AgentService(
        new ObjectMapper(), "", "gpt-5-mini", "workersai", "", "", "@cf/meta/llama-3.3-70b-instruct-fp8-fast");
    IntakeRequest request = new IntakeRequest(
        "Se corto la luz en toda la casa, salto el tablero en El Pinar",
        "Sofia", "098765432", "whatsapp"
    );

    IntakeResponse response = service.classify(request);

    assertEquals("cliente", response.leadType());
    assertEquals("electricidad", response.serviceCategory());
    assertEquals("El Pinar", response.area());
    assertEquals("alta", response.urgency());
  }

  @Test
  void classifyKeepsCurrentBehaviorForOpenAiProviderWithoutKey() {
    // Comportamiento intacto: provider=openai (o default) sin api key -> heurística, como hoy.
    AgentService service = new AgentService(
        new ObjectMapper(), "", "gpt-5-mini", "openai", "", "", "");
    IntakeRequest request = new IntakeRequest(
        "Se rompio la ducha y pierde agua sin parar en Solymar", null, null, "web-app"
    );

    IntakeResponse response = service.classify(request);

    assertEquals("plomeria", response.serviceCategory());
    assertEquals("Solymar", response.area());
  }
}
