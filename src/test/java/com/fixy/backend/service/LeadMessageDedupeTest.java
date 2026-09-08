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
 * Guard anti-loro de postFromAgent. Nació con el lead #109 (el agente posteó
 * dos veces seguidas el mismo enlatado arriba de una conversación
 * cliente↔proveedor) y desde el BUG B de las guardias del 2026-09-02 y 03/09
 * compara contra el último mensaje DEL AGENTE, no contra el último del chat:
 * el bucle que mata conversaciones es "pregunto X → me contestan → repregunto
 * X idéntico" (lead #265, "pocitos"), y el mensaje del cliente en el medio
 * desactivaba el guard viejo.
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
    lead.setAccessToken("tok-dedupe-" + System.nanoTime());
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

  /**
   * El caso del lead #265, en su forma mínima: el agente pregunta, el vecino
   * contesta, y el agente vuelve con la MISMA frase. Antes pasaba: el guard
   * miraba el último mensaje del chat, que era el del cliente.
   */
  @Test
  void agentDoesNotRepeatQuestionTheCustomerAlreadyAnswered() {
    Lead lead = persistLead();

    leadMessageService.postFromAgent(lead.getId(), "¿En qué zona estás?");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "pocitos");
    leadMessageService.postFromAgent(lead.getId(), "¿En qué zona estás?");

    long fixyCount = messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .count();
    assertThat(fixyCount).isEqualTo(1);
  }

  /**
   * La contracara: la repetición declarada en el call-site sí pasa. Es la
   * segunda nota de voz ilegible seguida — el vecino necesita saber que esa
   * tampoco se entendió, y callarse ahí sería el bug opuesto al del loro.
   */
  @Test
  void deliberateRepeatDeclaredByTheCallSiteStillGoesThrough() {
    Lead lead = persistLead();

    leadMessageService.postFromAgentAllowingRepeat(lead.getId(),
        "No pude escuchar bien la nota de voz. ¿Me lo escribís?");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "no se escucha?");
    leadMessageService.postFromAgentAllowingRepeat(lead.getId(),
        "No pude escuchar bien la nota de voz. ¿Me lo escribís?");

    long fixyCount = messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .count();
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
