package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guard anti-loro de postFromAgent (regresión del lead #109: el agente posteó
 * dos veces seguidas el mismo enlatado textual arriba de una conversación
 * cliente↔proveedor). Si el último mensaje del chat ya es el mismo texto del
 * agente, no se persiste de nuevo.
 */
@SpringBootTest
@Transactional
class LeadMessageDedupeTest {

  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageRepository messageRepository;

  private Lead persistLead() {
    Lead lead = new Lead();
    lead.setProblem("Pedido de prueba");
    lead.setChannel("web-app");
    lead.setStatus(LeadStatus.NEW);
    return leadRepository.save(lead);
  }

  @Test
  void agentDoesNotRepeatIdenticalConsecutiveMessage() {
    Lead lead = persistLead();

    leadMessageService.postFromAgent(lead.getId(), "La torta verde, ok. Te aviso cuando aparezca uno.");
    leadMessageService.postFromAgent(lead.getId(), "La torta verde, ok. Te aviso cuando aparezca uno.");

    long fixyCount = messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .count();
    assertThat(fixyCount).isEqualTo(1);
  }

  @Test
  void agentCanRepeatTextIfSomeoneSpokeInBetween() {
    Lead lead = persistLead();

    leadMessageService.postFromAgent(lead.getId(), "¿En qué zona estás?");
    leadMessageService.postFromOps(lead.getId(), "provider", "hola?");
    leadMessageService.postFromAgent(lead.getId(), "¿En qué zona estás?");

    long fixyCount = messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .count();
    // El guard es solo para repeticiones consecutivas: si alguien habló en
    // el medio, repetir la pregunta es legítimo.
    assertThat(fixyCount).isEqualTo(2);
  }

  @Test
  void differentConsecutiveAgentMessagesBothPersist() {
    Lead lead = persistLead();

    leadMessageService.postFromAgent(lead.getId(), "Anotado: pastelería.");
    leadMessageService.postFromAgent(lead.getId(), "¿Para cuándo la necesitás?");

    long fixyCount = messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .count();
    assertThat(fixyCount).isEqualTo(2);
  }
}
