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
 * Guard determinista contra zonas alucinadas por el LLM (leads #111/#112 en
 * prod: el 8B extrajo "Ciudad de la Costa" de las preguntas del propio agente
 * aún con la regla dura en el prompt). Una zona extraída solo se acepta si el
 * cliente la escribió textualmente en algún mensaje suyo.
 */
@SpringBootTest
@Transactional
class LeadAgentZoneGuardTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadRepository leadRepository;

  private Lead persistLead() {
    Lead lead = new Lead();
    lead.setProblem("Pedido de prueba");
    lead.setChannel("web-app");
    lead.setStatus(LeadStatus.NEW);
    lead.setAccessToken("tok-zone-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  @Test
  void zoneWrittenByCustomerIsAccepted() {
    Lead lead = persistLead();
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "Estoy en Solymar, se me rompió una canilla");

    assertThat(leadAgentService.zoneMentionedByCustomer(lead.getId(), "Solymar")).isTrue();
  }

  @Test
  void zoneAcceptedIgnoringAccentsAndCase() {
    Lead lead = persistLead();
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "vivo en shangrila");

    // El LLM devuelve la variante con tilde del catálogo.
    assertThat(leadAgentService.zoneMentionedByCustomer(lead.getId(), "Shangrilá")).isTrue();
  }

  @Test
  void zoneOnlyMentionedByAgentIsRejected() {
    Lead lead = persistLead();
    // El agente pregunta mencionando la zona — el cliente nunca la dijo.
    leadMessageService.postFromAgent(lead.getId(), "¿En qué zona de Ciudad de la Costa estás?");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "Se me rompió una canilla");

    assertThat(leadAgentService.zoneMentionedByCustomer(lead.getId(), "Ciudad de la Costa")).isFalse();
  }

  @Test
  void blankOrNullZoneIsRejected() {
    Lead lead = persistLead();
    assertThat(leadAgentService.zoneMentionedByCustomer(lead.getId(), null)).isFalse();
    assertThat(leadAgentService.zoneMentionedByCustomer(lead.getId(), "  ")).isFalse();
  }
}
