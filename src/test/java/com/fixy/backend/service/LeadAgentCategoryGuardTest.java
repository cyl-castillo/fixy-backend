package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guard determinista contra categorías alucinadas por el LLM (leads #116/#119
 * en prod: el 8B le presumió "pastelería" a un cliente que solo dijo "hola").
 * Una categoría extraída solo se acepta si el cliente dio algún rastro de
 * ella en sus propios mensajes — mismo patrón que LeadAgentZoneGuardTest.
 */
@SpringBootTest
@Transactional
class LeadAgentCategoryGuardTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadRepository leadRepository;

  private Lead persistLead() {
    Lead lead = new Lead();
    lead.setProblem("Pedido de prueba");
    lead.setChannel("web-app");
    lead.setStatus(LeadStatus.NEW);
    lead.setAccessToken("tok-category-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  @Test
  void categoryWithCustomerKeywordIsAccepted() {
    Lead lead = persistLead();
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "se me tapó la canilla, pierde agua");

    assertThat(leadAgentService.categoryMentionedByCustomer(lead.getId(), "plomeria")).isTrue();
  }

  @Test
  void categoryAcceptedIgnoringAccentsAndCase() {
    Lead lead = persistLead();
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "Necesito arreglar el JARDIN, el CESPED esta largo");

    assertThat(leadAgentService.categoryMentionedByCustomer(lead.getId(), "jardineria")).isTrue();
  }

  @Test
  void categoryWithNoTraceInCustomerTextIsRejected() {
    Lead lead = persistLead();
    // El cliente solo saludó — el LLM alucina "pastelería" (caso real #116/#119).
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "hola");

    assertThat(leadAgentService.categoryMentionedByCustomer(lead.getId(), "pasteleria")).isFalse();
  }

  @Test
  void categoryOnlyMentionedByAgentIsRejected() {
    Lead lead = persistLead();
    leadMessageService.postFromAgent(lead.getId(), "¿Es para una torta de cumpleaños?");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "hola, necesito ayuda");

    assertThat(leadAgentService.categoryMentionedByCustomer(lead.getId(), "pasteleria")).isFalse();
  }

  @Test
  void blankOrNullCategoryIsRejected() {
    Lead lead = persistLead();
    assertThat(leadAgentService.categoryMentionedByCustomer(lead.getId(), null)).isFalse();
    assertThat(leadAgentService.categoryMentionedByCustomer(lead.getId(), "  ")).isFalse();
  }

  @Test
  void unknownCategoryIdIsRejected() {
    Lead lead = persistLead();
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "necesito plomero urgente");

    assertThat(leadAgentService.categoryMentionedByCustomer(lead.getId(), "no-existe")).isFalse();
  }

  @Test
  void otroCategoryIsRejected() {
    Lead lead = persistLead();
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "hola, tengo un problema raro");

    assertThat(leadAgentService.categoryMentionedByCustomer(lead.getId(), "otro")).isFalse();
  }
}
