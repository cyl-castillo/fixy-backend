package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Salto 2 del cerebro agéntico (tool-calling explícito, ver
 * ARQUITECTURA_SUPERAPP.md): extiende el JSON forzado del turno conversacional
 * con un campo opcional "action". Verifica LeadAgentService.parseTurnJson
 * directo (package-private, mismo enfoque que LeadAgentCustomerMemoryTest usa
 * para buildContext) para no depender de un LLM real.
 *
 * Contrato: "action" ausente, "none", o de tipo desconocido siempre resuelve
 * a AgentAction.NONE — el comportamiento de antes de este campo existir queda
 * intacto byte-por-byte (mismo reply/extracted que ya parseaba parseTurnJson).
 * Solo "escalate" activa el dispatcher.
 */
@SpringBootTest
class LeadAgentActionParsingTest {

  @Autowired
  private LeadAgentService leadAgentService;

  @Test
  void actionAbsent_parsesAsNone_sameReplyAndExtractedAsBefore() {
    String raw = """
        {
          "reply": "Anotado, ¿en qué zona estás?",
          "extracted": {"category": "plomeria", "zone": null, "urgency": null, "phone": null, "name": null, "address": null, "details": null}
        }
        """;

    LeadAgentService.AgentTurnResult result = leadAgentService.parseTurnJson(raw);

    assertThat(result.reply()).isEqualTo("Anotado, ¿en qué zona estás?");
    assertThat(result.extracted()).containsEntry("category", "plomeria");
    assertThat(result.action()).isNotNull();
    assertThat(result.action().isEscalate()).isFalse();
    assertThat(result.action().type()).isEqualTo("none");
  }

  @Test
  void actionExplicitNone_parsesAsNone() {
    String raw = """
        {
          "reply": "Dale, seguimos.",
          "extracted": {},
          "action": {"type": "none", "reason": null, "summary": null}
        }
        """;

    LeadAgentService.AgentTurnResult result = leadAgentService.parseTurnJson(raw);

    assertThat(result.reply()).isEqualTo("Dale, seguimos.");
    assertThat(result.action().isEscalate()).isFalse();
  }

  @Test
  void actionUnknownType_fallsBackToNone() {
    String raw = """
        {
          "reply": "Ok.",
          "extracted": {},
          "action": {"type": "reschedule_provider", "reason": "algo", "summary": "algo"}
        }
        """;

    LeadAgentService.AgentTurnResult result = leadAgentService.parseTurnJson(raw);

    assertThat(result.action().isEscalate()).isFalse();
    assertThat(result.action().type()).isEqualTo("none");
  }

  @Test
  void actionEscalate_parsesReasonAndSummary() {
    String raw = """
        {
          "reply": "Te paso con una persona de Fixy para resolver esto mejor, en breve te contactan.",
          "extracted": {},
          "action": {"type": "escalate", "reason": "cliente frustrado por proveedor que no vino", "summary": "cliente reclama que el plomero no llegó"}
        }
        """;

    LeadAgentService.AgentTurnResult result = leadAgentService.parseTurnJson(raw);

    assertThat(result.action().isEscalate()).isTrue();
    assertThat(result.action().reason()).isEqualTo("cliente frustrado por proveedor que no vino");
    assertThat(result.action().summary()).isEqualTo("cliente reclama que el plomero no llegó");
  }

  @Test
  void malformedJson_stillFallsBackToNoneAction() {
    String raw = "esto no es JSON en absoluto";

    LeadAgentService.AgentTurnResult result = leadAgentService.parseTurnJson(raw);

    // Mismo comportamiento de fallback de antes: todo el raw como reply, sin extracción.
    assertThat(result.reply()).isEqualTo(raw);
    assertThat(result.extracted()).isEmpty();
    assertThat(result.action().isEscalate()).isFalse();
  }
}
