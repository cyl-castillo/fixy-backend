package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Incidente 2026-07-24: los leads de chat con [smoke] en el MENSAJE perdían
 * la marca al derivarse el problem ("Pedido de X") — y todos los guards
 * anti-tráfico-sintético miran problem. El banco de evaluación de modelos
 * terminó disparando pushes y avisos de matching estancado a proveedores
 * REALES. Este test protege la preservación de la marca de punta a punta.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Mismas props que LeadAgentFallbackTest: agente prendido sin credenciales
// de Cloudflare → el turno cae al fallback heurístico (determinista).
@org.springframework.test.context.TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
})
class SmokeTagPreservationTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private LeadRepository leadRepository;

  @Autowired
  private LeadEventRepository leadEventRepository;

  /**
   * Convención de Carlos (2026-07-30): sus pruebas manuales empiezan con la
   * palabra "smoke" a secas, sin corchetes. El problem derivado debe quedar
   * con la marca canónica "[smoke]" igual, para que todos los guards
   * (SmokeTraffic.marks) la reconozcan.
   */
  @Test
  void laPalabraSmokeSinCorchetesTambienMarcaElProblem() throws Exception {
    MvcResult chat = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer leadId = JsonPath.read(chat.getResponse().getContentAsString(), "$.id");
    String token = JsonPath.read(chat.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"smoke Necesito pastelería: una torta para el sábado, en Solymar\"}"))
        .andExpect(status().isCreated());

    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Lead lead = leadRepository.findById(Long.valueOf(leadId)).orElseThrow();
          assertThat(lead.getProblem()).isNotEqualTo("(pendiente)");
        });

    Lead lead = leadRepository.findById(Long.valueOf(leadId)).orElseThrow();
    assertThat(lead.getProblem()).startsWith("[smoke]");
  }

  @Test
  void elProblemDerivadoDeUnChatSmokeConservaLaMarca() throws Exception {
    MvcResult chat = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer leadId = JsonPath.read(chat.getResponse().getContentAsString(), "$.id");
    String token = JsonPath.read(chat.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"[smoke] Necesito plomería: pérdida de agua, en Solymar\"}"))
        .andExpect(status().isCreated());

    // El agente procesa async (fallback heurístico en tests, LLM apagado):
    // esperar a que el problem deje de ser "(pendiente)".
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Lead lead = leadRepository.findById(Long.valueOf(leadId)).orElseThrow();
          assertThat(lead.getProblem()).isNotEqualTo("(pendiente)");
        });

    Lead lead = leadRepository.findById(Long.valueOf(leadId)).orElseThrow();
    assertThat(lead.getProblem())
        .as("el problem derivado debe conservar la marca [smoke] del mensaje original")
        .startsWith("[smoke]");
  }
}
