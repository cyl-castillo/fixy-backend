package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.ServiceCategory;
import com.fixy.backend.repository.LeadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guion de intake por categoría (regresión del lead #123: el 8B le preguntó
 * "¿cuántas porciones?" a un pedido de AIRE ACONDICIONADO — mezcló el guion
 * de pastelería). El contexto del turno ahora le dice explícitamente qué
 * preguntar para cada servicio.
 */
@SpringBootTest
@Transactional
class LeadAgentIntakeHintTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadRepository leadRepository;

  private Lead persistLead(String category) {
    Lead lead = new Lead();
    lead.setProblem("Pedido de prueba");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.NEW);
    lead.setDetectedCategory(category);
    lead.setLocation("Lomas de Solymar");
    return leadRepository.save(lead);
  }

  @Test
  void airesGetsItsOwnIntakeScriptNotAnotherCategorys() {
    Lead lead = persistLead("aires_acondicionados");

    String context = leadAgentService.buildContext(lead);

    assertThat(context).contains("instalación, service/limpieza o reparación");
    assertThat(context).contains("NUNCA preguntes");
    assertThat(context).doesNotContain("porciones");
  }

  @Test
  void pasteleriaGetsPeopleAndDateQuestions() {
    Lead lead = persistLead("pasteleria");

    String context = leadAgentService.buildContext(lead);

    assertThat(context).contains("para cuándo lo necesita, para cuántas personas");
  }

  @Test
  void unknownCategoryGetsNoScript() {
    Lead lead = persistLead(null);

    String context = leadAgentService.buildContext(lead);

    assertThat(context).doesNotContain("lo útil de preguntar es");
  }

  @Test
  void everyMvpCategoryHasAScript() {
    for (String id : ServiceCategory.MVP_IDS) {
      assertThat(ServiceCategory.intakeHintForId(id))
          .as("categoría MVP '%s' debería tener guion de intake", id)
          .isNotBlank();
    }
  }

  @Test
  void provisionalCategoryInjectsHintOnFirstTurn() {
    // Primer turno: el lead todavía NO tiene categoría (la extracción corre
    // después), pero el mensaje del cliente ya la delata — la pre-clasificación
    // determinista mete el guion correcto desde el arranque.
    Lead lead = persistLead(null);

    String context = leadAgentService.buildContext(lead, "aires_acondicionados");

    assertThat(context).contains("instalación, service/limpieza o reparación");
    assertThat(context).doesNotContain("porciones");
  }
}
