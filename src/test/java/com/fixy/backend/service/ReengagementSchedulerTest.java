package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-enganche de leads mudos (caso real: #116/#118, visitantes que abren el
 * chat y no completan el intake). Conservador: un solo empujón por lead,
 * solo leads jóvenes, nunca cuando la respuesta la debe el agente.
 *
 * silence-minutes=0 en el test para no depender del reloj: el gate temporal
 * queda cubierto por el caso "último mensaje del cliente" y el de max-age.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.reengagement.silence-minutes=0",
    "fixy.reengagement.max-age-hours=24"
})
@Transactional
class ReengagementSchedulerTest {

  @Autowired private ReengagementScheduler scheduler;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageRepository messageRepository;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private jakarta.persistence.EntityManager entityManager;

  private Lead persistLead(String problem, LeadStatus status) {
    Lead lead = new Lead();
    lead.setProblem(problem);
    lead.setChannel("web-chat");
    lead.setStatus(status);
    lead.setAccessToken("tok-reeng-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  private void postAgentGreeting(Lead lead) {
    leadMessageService.postFromAgent(lead.getId(),
        "Hola, soy Fixy. Contame qué necesitás arreglar o coordinar y te ayudo a conseguir un proveedor.");
  }

  private List<LeadMessage> messagesOf(Lead lead) {
    return messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId());
  }

  @Test
  void visitorWhoNeverWroteGetsOneNudgeOnly() {
    Lead lead = persistLead("(pendiente)", LeadStatus.NEW);
    postAgentGreeting(lead);

    int first = scheduler.processOnce();
    int second = scheduler.processOnce();

    assertThat(first).isEqualTo(1);
    assertThat(second).isEqualTo(0);
    List<LeadMessage> msgs = messagesOf(lead);
    assertThat(msgs).hasSize(2);
    assertThat(msgs.get(1).getText()).contains("¿Seguís por ahí?");
    assertThat(msgs.get(1).getText()).contains("Sin compromiso");
  }

  @Test
  void stalledIntakeWithKnownCategoryMentionsIt() {
    Lead lead = persistLead("quiero una torta", LeadStatus.IN_REVIEW);
    lead.setDetectedCategory("pasteleria");
    leadRepository.save(lead);
    postAgentGreeting(lead);
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "quiero una torta");
    leadMessageService.postFromAgent(lead.getId(), "¿Para qué zona la necesitás?");

    int nudged = scheduler.processOnce();

    assertThat(nudged).isEqualTo(1);
    List<LeadMessage> msgs = messagesOf(lead);
    assertThat(msgs.get(msgs.size() - 1).getText()).contains("pastelería");
    assertThat(msgs.get(msgs.size() - 1).getText()).contains("quedó a medias");
  }

  @Test
  void customerWhoSpokeLastIsNeverNudged() {
    Lead lead = persistLead("(pendiente)", LeadStatus.NEW);
    postAgentGreeting(lead);
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "hola necesito ayuda");

    assertThat(scheduler.processOnce()).isEqualTo(0);
  }

  @Test
  void readyForMatchingAssignedSmokeAndOldLeadsAreSkipped() {
    Lead ready = persistLead("listo para match", LeadStatus.IN_REVIEW);
    ready.setReadyForMatching(true);
    leadRepository.save(ready);
    postAgentGreeting(ready);

    Lead assigned = persistLead("con proveedor", LeadStatus.NEW);
    assigned.setAssignedProviderId(99L);
    leadRepository.save(assigned);
    postAgentGreeting(assigned);

    Lead smoke = persistLead("[smoke] prueba", LeadStatus.NEW);
    postAgentGreeting(smoke);

    Lead old = persistLead("viejo", LeadStatus.NEW);
    postAgentGreeting(old);
    // createdAt lo fija @PrePersist — envejecemos el lead por SQL directo.
    entityManager.createNativeQuery("UPDATE leads SET created_at = :ts WHERE id = :id")
        .setParameter("ts", OffsetDateTime.now().minusHours(48))
        .setParameter("id", old.getId())
        .executeUpdate();
    entityManager.clear();

    assertThat(scheduler.processOnce()).isEqualTo(0);
  }
}
