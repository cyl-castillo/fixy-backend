package com.fixy.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifica que el body enviado a /responses de OpenAI sea correcto para ambas
 * familias de modelo, sin llamar a la red (no hay API key en esta máquina).
 *
 * gpt-5-* son reasoning models: por default usan "reasoning effort" medio, lo que
 * agrega latencia de razonamiento invisible. Para un chat conversacional de baja
 * latencia se fija "low" explícitamente. gpt-4.1 y anteriores no tienen ese campo
 * y deben mantener el payload minimal que ya funcionaba en producción.
 */
class AgentServiceOpenAiPayloadTest {

  @Test
  void gpt5ModelsGetLowReasoningEffort() {
    Map<String, Object> payload = AgentService.buildResponsesPayload("gpt-5-mini", "hola");

    assertEquals("gpt-5-mini", payload.get("model"));
    assertEquals("hola", payload.get("input"));
    assertTrue(payload.containsKey("reasoning"), "gpt-5-* debe mandar reasoning.effort");
    @SuppressWarnings("unchecked")
    Map<String, Object> reasoning = (Map<String, Object>) payload.get("reasoning");
    assertEquals("low", reasoning.get("effort"));
  }

  @Test
  void gpt5FamilyDetectionIsCaseInsensitiveAndCoversFullFamily() {
    for (String model : new String[] {"GPT-5-mini", "gpt-5", "gpt-5-nano", "gpt-5.1-mini"}) {
      Map<String, Object> payload = AgentService.buildResponsesPayload(model, "hola");
      assertTrue(payload.containsKey("reasoning"), "debe detectar familia gpt-5 para: " + model);
    }
  }

  @Test
  void legacyModelsKeepMinimalPayloadWithoutReasoningField() {
    Map<String, Object> payload = AgentService.buildResponsesPayload("gpt-4.1-mini", "hola");

    assertEquals("gpt-4.1-mini", payload.get("model"));
    assertEquals("hola", payload.get("input"));
    assertFalse(payload.containsKey("reasoning"), "gpt-4.1 no debe mandar reasoning.effort");
    assertEquals(2, payload.size(), "payload legacy debe seguir siendo minimal (model + input)");
  }

  @Test
  void unknownOrEmptyModelDefaultsToLegacyPayload() {
    Map<String, Object> payloadEmpty = AgentService.buildResponsesPayload("", "hola");
    Map<String, Object> payloadOther = AgentService.buildResponsesPayload("some-other-model", "hola");

    assertFalse(payloadEmpty.containsKey("reasoning"));
    assertFalse(payloadOther.containsKey("reasoning"));
  }
}
