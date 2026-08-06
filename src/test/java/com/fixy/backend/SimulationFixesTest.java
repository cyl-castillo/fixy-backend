package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.ServiceCategory;
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
 * Fixes de la simulación de 10 clientes (2026-08-06): teléfono en el texto
 * se captura por regex pase lo que pase con el LLM; "aire roto" clasifica;
 * categoría no cubierta responde con honestidad (no pide dirección para una
 * coordinación imposible); el precio ofrece próximo paso.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
})
class SimulationFixesTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageService leadMessageService;

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

  @Test
  void elTelefonoEscritoEnElMensajeSeCapturaPorRegex() throws Exception {
    ChatSession s = openChatAndSend(
        "[smoke] Necesito barométrica: el pozo desborda, en Solymar, mi teléfono es 099 888 111");
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
            leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getPhone())
            .isEqualTo("099888111"));
  }

  @Test
  void telefonoConPrefijo598TambienSeCaptura() {
    assertThat(LeadAgentService.phoneMentionedIn("llamame al +598 99 123 456")).isEqualTo("099123456");
    assertThat(LeadAgentService.phoneMentionedIn("mi cel es 099888111")).isEqualTo("099888111");
    // Un número que no es celular uruguayo no se captura (ni cédulas ni montos).
    assertThat(LeadAgentService.phoneMentionedIn("son 1234567 pesos")).isNull();
    assertThat(LeadAgentService.phoneMentionedIn("sin numero")).isNull();
  }

  @Test
  void aireRotoClasificaAires() {
    assertThat(ServiceCategory.detectFromText("aire roto"))
        .contains(ServiceCategory.AIRES_ACONDICIONADOS);
    // Pero "aire libre" no es aire acondicionado.
    assertThat(ServiceCategory.detectFromText("decoración para una fiesta al aire libre"))
        .contains(ServiceCategory.DECORACION_FIESTAS);
  }

  @Test
  void categoriaNoCubiertaRespondeConHonestidadYNoPideDireccion() throws Exception {
    ChatSession s = openChatAndSend(
        "[smoke] necesito un electricista urgente en Shangrilá se me corto la luz");
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          boolean honest = leadMessageService.recentForAgent(Long.valueOf(s.leadId()), 10).stream()
              .anyMatch(m -> !"customer".equals(m.getSender()) && m.getText() != null
                  && m.getText().contains("Todavía no tenemos proveedores de electricidad"));
          assertThat(honest).isTrue();
        });
    boolean asksAddress = leadMessageService.recentForAgent(Long.valueOf(s.leadId()), 10).stream()
        .anyMatch(m -> !"customer".equals(m.getSender()) && m.getText() != null
            && m.getText().contains("dirección exacta"));
    assertThat(asksAddress).as("no se pide dirección para una coordinación imposible").isFalse();
  }

  @Test
  void laPreguntaDeConfianzaRecibeRespuestaDigna() throws Exception {
    // Pastelería no tiene seed: el lead queda NEW sin proveedor — el caso
    // real de prod (con proveedor asignado el agente se calla a propósito:
    // la conversación es del proveedor).
    ChatSession s = openChatAndSend("[smoke] quiero una torta en Solymar");
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
            leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getDetectedCategory())
            .isEqualTo("pasteleria"));

    mockMvc.perform(post("/api/public/leads/{id}/messages", s.leadId())
            .param("token", s.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"quien es el que viene? es de confianza?\"}"))
        .andExpect(status().isCreated());

    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          boolean digna = leadMessageService.recentForAgent(Long.valueOf(s.leadId()), 10).stream()
              .anyMatch(m -> !"customer".equals(m.getSender()) && m.getText() != null
                  && m.getText().contains("verificados por el equipo"));
          assertThat(digna).as("la pregunta de confianza nunca recibe un guion genérico").isTrue();
        });
  }

  /**
   * Modelo Uber (equipo de Carlos 2026-08-06): Fixy acompaña al cliente
   * hasta que el proveedor ACEPTA; el pase de manos es explícito.
   */
  @Test
  void fixySigueRespondiendoHastaQueElProveedorAcepta() throws Exception {
    // Plomería en Solymar: automatch al seed → PROVIDER_CONTACTED (aún sin aceptar).
    ChatSession s = openChatAndSend("[smoke] Necesito plomería: pérdida de agua, en Solymar");
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(
            leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow().getStatus().name())
            .isEqualTo("PROVIDER_CONTACTED"));

    // El cliente pregunta mientras espera la aceptación: Fixy DEBE responder.
    mockMvc.perform(post("/api/public/leads/{id}/messages", s.leadId())
            .param("token", s.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"quien es el que viene? es de confianza?\"}"))
        .andExpect(status().isCreated());
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          boolean respondio = leadMessageService.recentForAgent(Long.valueOf(s.leadId()), 10).stream()
              .anyMatch(m -> !"customer".equals(m.getSender()) && m.getText() != null
                  && m.getText().contains("verificados por el equipo"));
          assertThat(respondio).as("Fixy responde mientras nadie aceptó").isTrue();
        });
  }

  @Test
  void elPrecioOfreceProximoPaso() throws Exception {
    ChatSession s = openChatAndSend("[smoke] cuanto sale instalar un split?");
    Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          boolean priceWithCta = leadMessageService.recentForAgent(Long.valueOf(s.leadId()), 10).stream()
              .anyMatch(m -> !"customer".equals(m.getSender()) && m.getText() != null
                  && m.getText().contains("rango orientativo")
                  && m.getText().contains("¿en qué zona estás?"));
          assertThat(priceWithCta).isTrue();
        });
    // Higiene: el lead no debería quedar sin registro del interés.
    Lead lead = leadRepository.findById(Long.valueOf(s.leadId())).orElseThrow();
    assertThat(lead.getDetectedCategory()).isEqualTo("aires_acondicionados");
  }
}
