package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Cotización Estimada (Ola 2 MVP, PLAN_SUPERAPP_CLIENTE.md): buildContext debe
 * inyectar el rango orientativo de precio cuando la categoría está definida y
 * tiene un rango cargado en ServiceCategory, con el disclaimer de que el
 * proveedor confirma el precio final — y NUNCA inventarlo sin categoría o sin
 * rango cargado. Mismo enfoque que LeadAgentCustomerMemoryTest: llama
 * buildContext directo (package-private) para no depender de un LLM real.
 */
@SpringBootTest
class LeadAgentPriceEstimateTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadRepository leadRepository;

  private Lead persistLead(String category, String location) {
    Lead lead = new Lead();
    lead.setProblem("Pedido de prueba");
    lead.setChannel("web-app");
    lead.setDetectedCategory(category);
    lead.setLocation(location);
    lead.setStatus(LeadStatus.NEW);
    return leadRepository.save(lead);
  }

  @Test
  void contextIncludesPriceRangeWhenCategoryHasOneLoaded() {
    Lead lead = persistLead("plomeria", "Solymar");

    String context = leadAgentService.buildContext(lead);

    assertThat(context).contains("rango orientativo de precio");
    assertThat(context).contains("$800–2500");
    assertThat(context).contains("proveedor confirma el precio final");
  }

  @Test
  void contextOmitsPriceRangeWhenCategoryUnknown() {
    Lead lead = persistLead(null, null);

    String context = leadAgentService.buildContext(lead);

    assertThat(context).doesNotContain("rango orientativo de precio");
  }

  @Test
  void contextOmitsPriceRangeWhenCategoryHasNoRangeLoaded() {
    // electricidad existe como categoría legacy reconocida por el clasificador
    // pero sin rango de precio cargado (placeholder pendiente de validar).
    Lead lead = persistLead("electricidad", "Solymar");

    String context = leadAgentService.buildContext(lead);

    assertThat(context).doesNotContain("rango orientativo de precio");
  }
}
