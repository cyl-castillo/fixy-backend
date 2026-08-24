package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.WhatsAppService;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MVP conversacional de CLIENTE por WhatsApp (ver WhatsAppInboundService).
 * Cubre dos escenarios, cero llamadas de red reales:
 *
 *  1. Credenciales vacías (canal deshabilitado, WHATSAPP_* sin setear en el
 *     entorno de test por defecto): el webhook POST sigue respondiendo 200 y
 *     el flujo de creación de lead + respuesta del agente sigue intacto
 *     (mismo comportamiento que hoy); WhatsAppService.isEnabled()==false
 *     así que sendText nunca hace la llamada HTTP real (ver
 *     WhatsAppService.sendText: primer chequeo es `if (!enabled) return
 *     false`) — no hace falta mockear la red para probar esto.
 *  2. Credenciales fake + WhatsAppService mockeado con @MockitoBean (mismo
 *     patrón que MercadoPagoWebhookControllerTest para MercadoPagoService):
 *     mensaje entrante de un número desconocido → lead creado con
 *     channel=whatsapp → el agente responde → la respuesta se intenta
 *     enviar por sendText(). Verificamos la invocación, no la red.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    // El agente conversacional debe estar activo para que greet()/
    // respondToCustomerAsync() posteen algo (por defecto en test está
    // deshabilitado, ver src/test/resources/application.yml). Sin
    // credenciales de LLM (openai.api-key="" heredado del yml de test),
    // el agente usa su fallback determinista heurístico — mismo patrón
    // que LeadAgentFallbackTest, cero llamadas de red reales.
    "fixy.agent.enabled=true"
})
class WhatsAppWebhookCustomerFlowTest {

  @Autowired
  private org.springframework.test.web.servlet.MockMvc mockMvc;

  @Autowired
  private LeadRepository leadRepository;

  @Autowired
  private LeadMessageRepository leadMessageRepository;

  @MockitoBean
  private WhatsAppService whatsappService;

  @Test
  void firstMessageFromUnknownNumberCreatesWhatsappLeadAndGreets() throws Exception {
    when(whatsappService.isEnabled()).thenReturn(false);

    String from = "59899777001";
    postInboundTextMessage(from, "wamid.customer1", "hola necesito un plomero");

    Lead lead = awaitLeadFor(from);
    assertThat(lead.getChannel()).isEqualTo("whatsapp");
    assertThat(lead.getPhone()).isEqualTo(from);

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          List<LeadMessage> messages = leadMessageRepository
              .findByLeadIdOrderByCreatedAtAsc(lead.getId());
          assertThat(messages).extracting(LeadMessage::getSender).contains("customer", "fixy");
        });
  }

  @Test
  void secondMessageFromSameNumberContinuesSameLeadInsteadOfCreatingAnother() throws Exception {
    when(whatsappService.isEnabled()).thenReturn(false);

    String from = "59899777002";
    postInboundTextMessage(from, "wamid.customer2a", "hola");
    Lead firstLead = awaitLeadFor(from);

    postInboundTextMessage(from, "wamid.customer2b", "se me rompio la canilla, estoy en Solymar");

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          List<LeadMessage> messages = leadMessageRepository
              .findByLeadIdOrderByCreatedAtAsc(firstLead.getId());
          assertThat(messages).extracting(LeadMessage::getSender)
              .filteredOn("customer"::equals).hasSize(2);
        });

    List<Lead> allForPhone = leadRepository.findByPhoneAndChannelOrderByCreatedAtDesc(from, "whatsapp");
    assertThat(allForPhone).hasSize(1);
  }

  @Test
  void agentReplyIsSentBackThroughWhatsappWhenChannelEnabled() throws Exception {
    when(whatsappService.isEnabled()).thenReturn(true);
    when(whatsappService.sendText(anyString(), anyString())).thenReturn(true);

    String from = "59899777003";
    postInboundTextMessage(from, "wamid.customer3", "hola, se me tapo un caño en Solymar");

    Lead lead = awaitLeadFor(from);

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> verify(whatsappService, times(2)).sendText(eq(from), anyString()));
    // 2 envíos: el saludo fijo del greet() chat-first + la respuesta al primer mensaje real.

    List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId());
    assertThat(messages).extracting(LeadMessage::getSender).contains("fixy");
  }

  @Test
  void menuRowSelectionSetsCategoryDirectlyWithoutClassifier() throws Exception {
    when(whatsappService.isEnabled()).thenReturn(false);

    String from = "59899777004";
    postInboundTextMessage(from, "wamid.customer4a", "hola");
    Lead lead = awaitLeadFor(from);
    assertThat(lead.getDetectedCategory()).isNull();

    postInboundListReplyMessage(from, "wamid.customer4b", "plomeria", "Plomería");

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          Lead updated = leadRepository.findById(lead.getId()).orElseThrow();
          assertThat(updated.getDetectedCategory()).isEqualTo("plomeria");
        });

    List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId());
    assertThat(messages).extracting(LeadMessage::getSender).contains("fixy");
  }

  @Test
  void otherRowSelectionCreatesLeadWithoutFakingProblemText() throws Exception {
    when(whatsappService.isEnabled()).thenReturn(false);

    String from = "59899777005";
    postInboundListReplyMessage(from, "wamid.customer5", "otro", "Otro / escribir");

    Lead lead = awaitLeadFor(from);
    assertThat(lead.getChannel()).isEqualTo("whatsapp");
    // No debe quedar el título de la fila colado como si fuera la
    // descripción real del problema.
    assertThat(lead.getDetectedCategory()).isNull();
    assertThat(lead.getProblem()).doesNotContain("Otro / escribir");
  }

  @Test
  void webhookVerificationStillWorksWithoutCredentials() throws Exception {
    // Comportamiento existente intacto: sin verify-token configurado en el
    // entorno de test, el challenge de Meta se rechaza (no hay token con el
    // cual matchear), pero el endpoint responde sin romper — 403, no 500.
    mockMvc.perform(get("/api/webhooks/whatsapp")
            .param("hub.mode", "subscribe")
            .param("hub.verify_token", "cualquier-cosa")
            .param("hub.challenge", "12345"))
        .andExpect(status().isForbidden());
  }

  private void postInboundTextMessage(String from, String messageId, String text) throws Exception {
    String payload = """
        {
          "entry": [{
            "changes": [{
              "value": {
                "messages": [{
                  "from": "%s",
                  "id": "%s",
                  "type": "text",
                  "text": {"body": "%s"}
                }]
              }
            }]
          }]
        }
        """.formatted(from, messageId, text);
    MvcResult result = mockMvc.perform(post("/api/webhooks/whatsapp")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(result.getResponse().getContentAsString()).isEqualTo("OK");
  }

  private void postInboundListReplyMessage(String from, String messageId, String rowId, String rowTitle) throws Exception {
    String payload = """
        {
          "entry": [{
            "changes": [{
              "value": {
                "messages": [{
                  "from": "%s",
                  "id": "%s",
                  "type": "interactive",
                  "interactive": {
                    "type": "list_reply",
                    "list_reply": {"id": "%s", "title": "%s"}
                  }
                }]
              }
            }]
          }]
        }
        """.formatted(from, messageId, rowId, rowTitle);
    MvcResult result = mockMvc.perform(post("/api/webhooks/whatsapp")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isOk())
        .andReturn();
    assertThat(result.getResponse().getContentAsString()).isEqualTo("OK");
  }

  private Lead awaitLeadFor(String phone) {
    java.util.concurrent.atomic.AtomicReference<Lead> found = new java.util.concurrent.atomic.AtomicReference<>();
    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> {
          List<Lead> leads = leadRepository.findByPhoneAndChannelOrderByCreatedAtDesc(phone, "whatsapp");
          assertThat(leads).isNotEmpty();
          found.set(leads.get(0));
        });
    return found.get();
  }
}
