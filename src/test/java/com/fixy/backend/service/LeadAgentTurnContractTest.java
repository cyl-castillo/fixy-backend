package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.domain.Playbook;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 2, pieza 3 (compuerta 4): el schema del turno y el bloque FORMATO DE SALIDA salen del
 * playbook pero son EXACTAMENTE los de antes: mismo Map (mismos valores, mismo orden de enums) y
 * mismo texto. Los literales son los del código anterior a esta fase.
 */
class LeadAgentTurnContractTest {

  private static String resource(String path) {
    try (InputStream in = LeadAgentTurnContractTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Test
  void elSchemaDelTurnoEsIdenticoAlDeAntes() {
    Map<String, Object> expected = Map.of(
        "type", "object",
        "properties", Map.of(
            "reply", Map.of("type", "string"),
            "extracted", Map.of(
                "type", "object",
                "properties", Map.of(
                    "category", Map.of("type", "string", "enum", List.of(
                        "plomeria", "barometrica", "jardineria", "aires_acondicionados", "pasteleria",
                        "decoracion_fiestas", "mandados", "otro")),
                    "zone", Map.of("type", "string", "enum", List.of(
                        "Solymar", "Lagomar", "El Pinar", "Shangrilá", "Barra de Carrasco",
                        "Parque Miramar", "San José de Carrasco", "Lomas de Solymar", "Montes de Solymar",
                        "Colinas de Solymar", "Aeroparque", "Ciudad de la Costa", "otro")),
                    "urgency", Map.of("type", "string", "enum", List.of("alta", "media", "baja")),
                    "phone", Map.of("type", "string"),
                    "name", Map.of("type", "string"),
                    "address", Map.of("type", "string"),
                    "details", Map.of("type", "string")
                )
            ),
            "action", Map.of(
                "type", "object",
                "properties", Map.of(
                    "type", Map.of("type", "string", "enum", List.of("none", "escalate")),
                    "reason", Map.of("type", "string"),
                    "summary", Map.of("type", "string")
                )
            )
        ),
        "required", List.of("reply", "extracted")
    );

    assertThat(LeadAgentService.turnJsonSchema()).isEqualTo(expected);
  }

  /** TAREA + FORMATO DE SALIDA + reglas, con el bloque de formato generado por el playbook. */
  @Test
  void lasInstruccionesDelTurnoSonIdenticasALasDeAntes() {
    assertThat(LeadAgentService.turnInstructions())
        .isEqualTo(resource("/prompts/expected/lead-turn-instructions.md"));
  }

  @Test
  void elBloqueDeFormatoDeSalidaEsElDeAntes() {
    assertThat(Playbook.get().outputFormatBlock()).isEqualTo("""
        FORMATO DE SALIDA: SOLO un JSON válido, sin texto antes ni después, con esta estructura:
        {
          "reply": "tu respuesta conversacional al cliente",
          "extracted": {
            "category": "plomeria|barometrica|jardineria|aires_acondicionados|pasteleria|decoracion_fiestas|mandados|otro|null",
            "zone": "Solymar|Lagomar|El Pinar|Shangrilá|Barra de Carrasco|Parque Miramar|San José de Carrasco|Lomas de Solymar|Montes de Solymar|Colinas de Solymar|Aeroparque|Ciudad de la Costa|otro|null",
            "urgency": "alta|media|baja|null",
            "phone": "099XXXXXX o null",
            "name": "nombre o null",
            "address": "dirección exacta o null",
            "details": "detalles relevantes o null"
          },
          "action": {
            "type": "none|escalate",
            "reason": "motivo corto del escalamiento, o null si type es none",
            "summary": "resumen de 1 línea de la situación para la persona que va a atender, o null si type es none"
          }
        }""");
  }

  /** parseTurnJson extrae exactamente los campos que declara el playbook: si divergen, este test rompe. */
  @Test
  void parseTurnJsonExtraeLosCamposQueDeclaraElPlaybook() throws Exception {
    Map<String, Object> extracted = new java.util.LinkedHashMap<>();
    Playbook.get().fields().forEach(f -> extracted.put(f.name(), "valor-" + f.name()));
    String raw = new ObjectMapper().writeValueAsString(
        Map.of("reply", "hola", "extracted", extracted));

    LeadAgentService.AgentTurnResult result = new LeadAgentService(
        new ObjectMapper(), null, null, null, null, null, null, null, null, null, null, null, null)
        .parseTurnJson(raw);

    assertThat(result.extracted().keySet())
        .containsExactlyInAnyOrderElementsOf(Playbook.get().fields().stream().map(Playbook.Field::name).toList());
  }
}
