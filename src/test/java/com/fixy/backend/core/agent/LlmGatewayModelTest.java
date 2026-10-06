package com.fixy.backend.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Core Fase 3: {@code LlmGateway.model()} devuelve el modelo del proveedor que despacha el turno. */
class LlmGatewayModelTest {

  private static LlmGateway gateway(String provider) {
    return new LlmGateway(new ObjectMapper(), "", "modelo-openai", true, provider,
        "http://127.0.0.1:11434", "modelo-ollama", "", "", "modelo-cloudflare");
  }

  @Test
  void elModeloSigueAlProveedorConfigurado() {
    assertThat(gateway("openai").model()).isEqualTo("modelo-openai");
    assertThat(gateway("OpenAI ").model()).isEqualTo("modelo-openai");
    assertThat(gateway("workersai").model()).isEqualTo("modelo-cloudflare");
    assertThat(gateway("ollama").model()).isEqualTo("modelo-ollama");
    // Mismo despacho que completeText: lo desconocido cae a OpenAI.
    assertThat(gateway("otro").model()).isEqualTo("modelo-openai");
  }

  @Test
  void promptVersionEsUnSha256CortoYEstable() {
    String a = AgentTurnRecorder.promptVersion("plantilla");
    assertThat(a).matches("[0-9a-f]{12}").isEqualTo(AgentTurnRecorder.promptVersion("plantilla"));
    assertThat(AgentTurnRecorder.promptVersion("otra")).isNotEqualTo(a);
    assertThat(AgentTurnRecorder.promptVersion(null)).matches("[0-9a-f]{12}");
  }
}
