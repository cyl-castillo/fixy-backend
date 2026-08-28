package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * El fallback determinista no repite lo ya dicho (reporte de un cliente,
 * 2026-08-27: pidió aires acondicionados y recibió "Anotado: ... estoy
 * buscando uno para vos" varias veces seguidas — cada mensaje suyo en espera
 * caía al fallback y el builder devolvía siempre el mismo texto). En espera:
 * reconocimiento una vez, ante la insistencia el estado de la búsqueda, y si
 * eso también se dijo ya, silencio.
 *
 * Sin @Transactional: respondToCustomerAsync corre @Async en otro hilo
 * (mismo motivo que LeadAgentAckSilenceTest).
 */
@SpringBootTest
@TestPropertySource(properties = {
    // Agente encendido pero LLM sin credenciales (falla y cae al fallback
    // determinista): el mismo montaje que LeadAgentFallbackTest.
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token="
})
class LeadAgentFallbackRepeatTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository messageRepository;
  @Autowired private LeadRepository leadRepository;

  @Test
  void waitingLeadNeverGetsSameFallbackTwice() throws Exception {
    Lead lead = new Lead();
    lead.setProblem("Aire acondicionado que no enfría");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.IN_REVIEW);
    lead.setDetectedCategory("aires_acondicionados");
    lead.setLocation("Lomas de Solymar");
    lead.setUrgency("alta");
    lead.setReadyForMatching(true);
    lead.setAccessToken("tok-repeat-" + System.nanoTime());
    lead = leadRepository.save(lead);
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    // Turno 1: primer mensaje en espera → reconocimiento "Anotado: ...".
    leadMessageService.postFromCustomer(leadId, token, "necesito arreglar el aire de casa");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    String first = lastAgentText(leadId);
    assertThat(first).as("primer turno en espera reconoce el pedido").startsWith("Anotado:");

    // Turno 2: el cliente insiste → NO se repite el "Anotado", se responde estado.
    leadMessageService.postFromCustomer(leadId, token, "hola? alguna novedad de mi pedido");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    String second = lastAgentText(leadId);
    assertThat(second).as("segundo turno no repite el reconocimiento").isNotEqualTo(first);
    assertThat(second).doesNotStartWith("Anotado:");

    // Turno 3: insiste de nuevo y todo lo decible ya se dijo → silencio.
    leadMessageService.postFromCustomer(leadId, token, "sigo esperando respuesta");
    long before = messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).size();
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    long after = messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).size();
    assertThat(after).as("tercera insistencia no genera otro mensaje repetido")
        .isEqualTo(before); // before ya incluye el mensaje del cliente; el agente calla
  }

  private String lastAgentText(Long leadId) {
    List<LeadMessage> all = messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    for (int i = all.size() - 1; i >= 0; i--) {
      if ("fixy".equals(all.get(i).getSender())) {
        return all.get(i).getText();
      }
    }
    return null;
  }
}
