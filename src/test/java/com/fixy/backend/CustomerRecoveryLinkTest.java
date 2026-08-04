package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.LeadAgentService;
import com.fixy.backend.service.LeadMessageService;
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
 * Caso real lead #200 (2026-08-04): el cliente perdió su pedido — la sesión
 * anónima vive solo en el navegador donde se creó, no dejó WhatsApp, y el
 * proveedor quedó preguntando al vacío. Dos redes de seguridad nuevas:
 * (1) tras el matching, el agente comparte el link de recuperación /c/...;
 * (2) si el proveedor escribe y el cliente sigue sin teléfono, una única
 * insistencia por el WhatsApp.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
})
class CustomerRecoveryLinkTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private LeadRepository leadRepository;

  @Autowired
  private LeadMessageService leadMessageService;

  @Autowired
  private LeadAgentService leadAgentService;

  private record ChatSession(Integer leadId, String token) {}

  private ChatSession openChatAndSend(String text) throws Exception {
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
            .content("{\"text\": \"" + text + "\"}"))
        .andExpect(status().isCreated());
    return new ChatSession(leadId, token);
  }

  private boolean agentMessageContains(Long leadId, String needle) {
    return leadMessageService.recentForAgent(leadId, 40).stream()
        .anyMatch(m -> !"customer".equals(m.getSender())
            && m.getText() != null && m.getText().contains(needle));
  }

  @Test
  void trasElMatchingElAgenteComparteElLinkDeRecuperacionUnaSolaVez() throws Exception {
    // Plomería en Solymar: automatch real contra el seed → mensaje de
    // matching → link de recuperación con el token del propio lead.
    ChatSession s = openChatAndSend("[smoke] Necesito plomería: pérdida de agua, en Solymar");

    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
            agentMessageContains(Long.valueOf(s.leadId()), "/c/" + s.leadId() + "/")).isTrue());

    Lead lead = leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow();
    assertThat(agentMessageContains(Long.valueOf(s.leadId()), lead.getAccessToken()))
        .as("el link debe llevar el token real del pedido")
        .isTrue();

    long linkMessages = leadMessageService.recentForAgent(Long.valueOf(s.leadId()), 40).stream()
        .filter(m -> m.getText() != null && m.getText().contains("/c/" + s.leadId() + "/"))
        .count();
    assertThat(linkMessages).as("el link se comparte UNA vez").isEqualTo(1);
  }

  @Test
  void siElProveedorEscribeYNoHayTelefonoSeInsisteUnaUnicaVez() throws Exception {
    ChatSession s = openChatAndSend("[smoke] Necesito plomería: pérdida de agua, en Solymar");
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
            leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getAssignedProviderId())
            .isNotNull());

    leadAgentService.afterProviderMessage(Long.valueOf(s.leadId()));
    leadAgentService.afterProviderMessage(Long.valueOf(s.leadId()));

    long nudges = leadMessageService.recentForAgent(Long.valueOf(s.leadId()), 40).stream()
        .filter(m -> m.getText() != null && m.getText().contains("El proveedor te escribió"))
        .count();
    assertThat(nudges).as("una única insistencia aunque el proveedor escriba varias veces").isEqualTo(1);
  }

  @Test
  void conTelefonoYaDejadoNoSeInsiste() throws Exception {
    ChatSession s = openChatAndSend("[smoke] Necesito plomería: pérdida de agua, en Solymar");
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
            leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getAssignedProviderId())
            .isNotNull());
    Lead lead = leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow();
    lead.setPhone("099123456");
    leadRepository.save(lead);

    leadAgentService.afterProviderMessage(Long.valueOf(s.leadId()));

    assertThat(agentMessageContains(Long.valueOf(s.leadId()), "El proveedor te escribió")).isFalse();
  }
}
