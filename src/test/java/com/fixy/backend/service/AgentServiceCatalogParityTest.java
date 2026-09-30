package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.IntakeRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 2, paridad de AgentService: la detección de zona (prioridad de match incluida) y el
 * schema del clasificador salen del catálogo pero se comportan EXACTAMENTE como el código viejo
 * (ZONE_TOKENS / CANONICAL_AREAS / listas escritas a mano). Los casos esperados de la detección
 * salen de {@code domain/legacy-area-detection.json}, generado corriendo el código anterior.
 */
class AgentServiceCatalogParityTest {

  private static JsonNode legacy() {
    try (InputStream in = AgentServiceCatalogParityTest.class.getResourceAsStream("/domain/legacy-area-detection.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Test
  void areaMentionedInCoincideConElCodigoViejo() {
    AgentService agent = new AgentService(new ObjectMapper(), "", "gpt-5-mini");
    int checked = 0;
    for (JsonNode row : legacy().get("areaMentionedIn")) {
      String[] parts = row.asText().split("\t", -1);
      String expected = "null".equals(parts[1]) ? null : parts[1];
      assertThat(agent.areaMentionedIn(parts[0])).as("areaMentionedIn(\"%s\")", parts[0]).isEqualTo(expected);
      checked++;
    }
    assertThat(checked).isEqualTo(30);
  }

  @Test
  void normalizeAreaValueCoincideConElCodigoViejo() {
    int checked = 0;
    for (JsonNode row : legacy().get("normalizeAreaValue")) {
      String[] parts = row.asText().split("\t", -1);
      String in = "null".equals(parts[0]) ? null : parts[0];
      assertThat(AgentService.normalizeAreaValue(in)).as("normalizeAreaValue(%s)", in).isEqualTo(parts[1]);
      checked++;
    }
    assertThat(checked).isEqualTo(39);
  }

  /** Caso real lead #132: "montes" cayó a "Ciudad de la Costa"; las específicas ganan a "solymar" a secas. */
  @Test
  void lasZonasEspecificasGananASolymarASecas() {
    AgentService agent = new AgentService(new ObjectMapper(), "", "gpt-5-mini");
    assertThat(agent.areaMentionedIn("vivo en los montes")).isEqualTo("Montes de Solymar");
    assertThat(agent.areaMentionedIn("estoy en lomas de solymar")).isEqualTo("Lomas de Solymar");
    assertThat(agent.areaMentionedIn("colinas de solymar")).isEqualTo("Colinas de Solymar");
    assertThat(agent.areaMentionedIn("solymar")).isEqualTo("Solymar");
    // Con dos zonas en el mismo mensaje gana la de mayor prioridad, no la primera que aparece.
    assertThat(agent.areaMentionedIn("solymar y lomas")).isEqualTo("Lomas de Solymar");
    assertThat(agent.areaMentionedIn("lagomar y solymar")).isEqualTo("Lagomar");
  }

  @Test
  void elSchemaDelClasificadorEsIdenticoAlDeAntes() {
    Map<String, Object> expected = Map.of(
        "type", "object",
        "properties", Map.of(
            "leadType", Map.of("type", "string", "enum", List.of("cliente", "proveedor")),
            "serviceCategory", Map.of("type", "string", "enum", List.of(
                "plomeria", "electricidad", "cerrajeria", "barometrica", "jardineria",
                "aires_acondicionados", "reparaciones", "pasteleria", "decoracion_fiestas", "mandados", "otro")),
            "area", Map.of("type", "string", "enum", List.of(
                "Solymar", "Lagomar", "El Pinar", "Shangrilá", "Barra de Carrasco", "Parque Miramar",
                "San José de Carrasco", "Lomas de Solymar", "Colinas de Solymar", "Montes de Solymar",
                "Aeroparque", "Ciudad de la Costa", "sin definir")),
            "urgency", Map.of("type", "string", "enum", List.of("alta", "media", "baja")),
            "summary", Map.of("type", "string"),
            "missingFields", Map.of("type", "array", "items", Map.of("type", "string")),
            "suggestedReply", Map.of("type", "string")
        ),
        "required", List.of("leadType", "serviceCategory", "area", "urgency", "summary", "missingFields", "suggestedReply")
    );

    assertThat(AgentService.intakeJsonSchema()).isEqualTo(expected);
  }

  /**
   * normalizeServiceCategory (categoría ya declarada por el usuario) usa la lista CORTA
   * declaredKeywords del catálogo, no las keywords laxas del clasificador: mismos resultados que antes.
   */
  @Test
  void laCategoriaDeclaradaSeNormalizaComoAntes() {
    AgentService agent = new AgentService(new ObjectMapper(), "", "gpt-5-mini");
    Map<String, String> cases = Map.ofEntries(
        Map.entry("Plomería", "plomeria"), Map.entry("agua", "plomeria"), Map.entry("caño roto", "plomeria"),
        Map.entry("electricista", "electricidad"), Map.entry("sin luz", "electricidad"),
        Map.entry("cerrajero", "cerrajeria"), Map.entry("perdí la llave", "cerrajeria"),
        Map.entry("barometrica", "barometrica"),
        Map.entry("jardinero", "jardineria"), Map.entry("cortar el pasto", "jardineria"),
        Map.entry("split", "aires_acondicionados"), Map.entry("aire acondicionado", "aires_acondicionados"),
        Map.entry("torta", "pasteleria"), Map.entry("cumpleaños", "pasteleria"),
        Map.entry("globos", "decoracion_fiestas"), Map.entry("ambientación", "decoracion_fiestas"),
        // Sin coincidencia en la lista corta: se conserva lo declarado (normalizado).
        Map.entry("Carpintería", "carpintería"), Map.entry("mandados", "mandados"), Map.entry("aires", "aires"));
    cases.forEach((declared, expected) -> {
      String actual = agent.classify(new IntakeRequest(
          "necesito ayuda", "Ana", "099111222", "web-app", declared, null, null, null, null)).serviceCategory();
      assertThat(actual).as("categoría declarada \"%s\"", declared).isEqualTo(expected);
    });
  }
}
