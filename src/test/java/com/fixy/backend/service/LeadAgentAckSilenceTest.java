package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Silencio ante asentimientos en estado de espera (captura de Carlos,
 * 2026-07-16 17:47): el sistema dijo "no tengo proveedores, te aviso", el
 * cliente cerró con "ok" y el agente reabrió el interrogatorio. Un "ok" en
 * espera no se responde.
 *
 * Sin @Transactional: respondToCustomerAsync corre @Async en otro hilo
 * (mismo motivo que PasteleriaMatchingTest).
 */
@SpringBootTest
class LeadAgentAckSilenceTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository messageRepository;
  @Autowired private LeadRepository leadRepository;

  @Test
  void acknowledgmentLexiconMatchesShortClosers() {
    assertThat(LeadAgentService.isAcknowledgment("ok")).isTrue();
    assertThat(LeadAgentService.isAcknowledgment("Ok!")).isTrue();
    assertThat(LeadAgentService.isAcknowledgment("Gracias")).isTrue();
    assertThat(LeadAgentService.isAcknowledgment("dale")).isTrue();
    assertThat(LeadAgentService.isAcknowledgment("Bárbaro")).isTrue();

    assertThat(LeadAgentService.isAcknowledgment("ok pero tengo una duda")).isFalse();
    assertThat(LeadAgentService.isAcknowledgment("no enfría nada")).isFalse();
    assertThat(LeadAgentService.isAcknowledgment(null)).isFalse();
    assertThat(LeadAgentService.isAcknowledgment("")).isFalse();
  }

  @Test
  void ackWhileWaitingForProviderGetsNoReply() throws Exception {
    Lead lead = new Lead();
    lead.setProblem("Aire que no enfría");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.IN_REVIEW);
    lead.setDetectedCategory("aires_acondicionados");
    lead.setLocation("Lomas de Solymar");
    lead.setReadyForMatching(true);
    lead.setAccessToken("tok-ack-" + System.nanoTime());
    lead = leadRepository.save(lead);

    leadMessageService.postFromAgent(lead.getId(),
        "Por ahora no tengo proveedores libres. Te aviso por acá.");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "ok");
    long before = messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).size();

    leadAgentService.respondToCustomerAsync(lead.getId());
    Thread.sleep(1500); // dar tiempo al hilo async a (no) responder

    long after = messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).size();
    assertThat(after).as("un 'ok' en espera no debe generar respuesta").isEqualTo(before);
  }
}
