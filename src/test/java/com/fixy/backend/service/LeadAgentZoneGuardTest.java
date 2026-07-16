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

  @Test
  void partialZoneTokenFromCustomerValidatesCanonicalName() {
    Lead lead = persistLead();
    // Caso real lead #123: el cliente escribe "lomas", el LLM canonicaliza a
    // "Lomas de Solymar" — debe aceptarse.
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "lomas");

    assertThat(leadAgentService.zoneMentionedByCustomer(lead.getId(), "Lomas de Solymar")).isTrue();
  }

  @Test
  void stopwordsAloneDoNotValidateZone() {
    Lead lead = persistLead();
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "de la casa de mi madre");

    assertThat(leadAgentService.zoneMentionedByCustomer(lead.getId(), "Ciudad de la Costa")).isFalse();
  }

  @Test
  void stuckLlmReplyIsDetected() {
    Lead lead = persistLead();
    leadMessageService.postFromAgent(lead.getId(), "¿Es instalación, servicio o reparación?");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "servicio");

    assertThat(leadAgentService.isStuckRepeatingItself(lead.getId(),
        "¿Es instalación, servicio o reparación?")).isTrue();
    assertThat(leadAgentService.isStuckRepeatingItself(lead.getId(),
        "Dale, un service entonces. ¿Para cuándo lo necesitás?")).isFalse();
  }

  @Test
  void fuzzyRepeatWithDifferentPreambleIsDetected() {
    Lead lead = persistLead();
    leadMessageService.postFromAgent(lead.getId(),
        "Dale, necesitás aire acondicionado en Lomas. ¿Cuál es el problema? ¿Necesitás reparación o instalación?");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "reparacion");

    // Caso real lead #126: mismo contenido con preámbulo distinto.
    assertThat(leadAgentService.isStuckRepeatingItself(lead.getId(),
        "Dale, aire acondicionado en Lomas. ¿Necesitás reparación o instalación?")).isTrue();
    // Una respuesta que avanza de verdad no debe dispararlo.
    assertThat(leadAgentService.isStuckRepeatingItself(lead.getId(),
        "Dale, reparación anotada. Ya busco proveedor y te aviso por acá.")).isFalse();
  }

  @Test
  void repeatingAnOlderQuestionIsAlsoDetected() {
    Lead lead = persistLead();
    // Lead #131: preguntó tamaño, el cliente respondió, preguntó zona, y
    // después VOLVIÓ a preguntar el tamaño — dos mensajes atrás.
    leadMessageService.postFromAgent(lead.getId(), "Dale, necesitás jardinería. ¿Qué tamaño tiene el jardín?");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "30 m");
    leadMessageService.postFromAgent(lead.getId(), "Dale, necesitás jardinería. ¿Qué zona de Ciudad de la Costa?");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "montes");

    assertThat(leadAgentService.isStuckRepeatingItself(lead.getId(),
        "Dale, necesitás jardinería en Montes. ¿Qué tamaño tiene el jardín?")).isTrue();
  }

  @Test
  void montesDeSolymarIsAValidZoneToken() {
    Lead lead = persistLead();
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "montes");

    assertThat(leadAgentService.zoneMentionedByCustomer(lead.getId(), "Montes de Solymar")).isTrue();
  }
}
