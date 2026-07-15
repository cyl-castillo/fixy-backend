package com.fixy.backend;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.LeadAgentService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Regresión del desastre del lead #109 (primera conversación real
 * cliente↔proveedor): con el lead ASIGNADO a un proveedor, la conversación es
 * entre humanos y el agente NO debe responder a los mensajes del cliente.
 * Antes, con WhatsApp deshabilitado, el controller caía al else y el agente
 * interrumpía la charla del proveedor con enlatados repetidos.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AgentProviderConversationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @MockitoBean private LeadAgentService agentService;

  private record ChatLead(Long id, String token) {}

  private ChatLead startChat() throws Exception {
    MvcResult result = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isCreated())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    Integer id = JsonPath.read(body, "$.id");
    String token = JsonPath.read(body, "$.accessToken");
    return new ChatLead(id.longValue(), token);
  }

  private void postCustomerMessage(ChatLead chat, String text) throws Exception {
    mockMvc.perform(post("/api/public/leads/{id}/messages", chat.id())
            .param("token", chat.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"" + text + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void agentStaysSilentWhenLeadIsAssignedToProvider() throws Exception {
    ChatLead chat = startChat();
    Lead lead = leadRepository.findById(chat.id()).orElseThrow();
    lead.setAssignedProviderId(999L);
    lead.setStatus(LeadStatus.ASSIGNED);
    leadRepository.save(lead);

    postCustomerMessage(chat, "Para el viernes");

    // La charla es cliente↔proveedor: el agente no interrumpe, aunque
    // WhatsApp esté deshabilitado (como en prod hasta que Meta apruebe).
    verify(agentService, never()).respondToCustomerAsync(anyLong());
  }

  @Test
  void agentStillRespondsWhenLeadHasNoProvider() throws Exception {
    ChatLead chat = startChat();

    postCustomerMessage(chat, "Necesito un plomero en Solymar");

    verify(agentService, timeout(2000)).respondToCustomerAsync(chat.id());
  }

  @Test
  void agentStillRespondsWhenStatusAdvancedButNoProviderAssigned() throws Exception {
    ChatLead chat = startChat();
    Lead lead = leadRepository.findById(chat.id()).orElseThrow();
    // PROVIDER_CONTACTED sin proveedor confirmado: el agente sigue atendiendo.
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    leadRepository.save(lead);

    postCustomerMessage(chat, "Se me olvidó decir que es urgente");

    verify(agentService, timeout(2000)).respondToCustomerAsync(chat.id());
  }
}
