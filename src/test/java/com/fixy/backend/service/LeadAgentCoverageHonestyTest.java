package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regresión de la contradicción de los leads #105/#108/#109: el contexto que
 * se le da al LLM contaba proveedores con la etiqueta humana ("Pastelería")
 * mientras el catálogo matchea por id ("pasteleria") → contaba 0 y el agente
 * decía "no hay proveedores libres" justo antes de que el auto-match posteara
 * "Estoy contactando a Melissa". También se inyectaba el hint con categoría y
 * zona "sin definir" (primer mensaje del cliente).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LeadAgentCoverageHonestyTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadRepository leadRepository;

  /**
   * Alta por la API de ops + aprobación, como lo hace Carlos en el admin: el
   * alta nace {@code NEW} y desde el 2026-08-06 el estado {@code NEW} no
   * recibe trabajo ({@link ProviderCatalogService}), así que un proveedor de
   * fixture que tiene que matchear se aprueba explícitamente.
   */
  private void createProvider(String name, String phone, String zone, String category) throws Exception {
    String payload = """
        {
          "name": "%s",
          "phone": "%s",
          "primaryZone": "%s",
          "city": "Ciudad de la Costa",
          "categories": "%s"
        }
        """.formatted(name, phone, zone, category);
    MvcResult created = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    mockMvc.perform(patch("/api/providers/{id}", providerId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"AVAILABLE\"}"))
        .andExpect(status().isOk());
  }

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
  void contextCountsProvidersByCategoryIdNotHumanLabel() throws Exception {
    createProvider("Melissa Test", "099300001", "Ciudad de la Costa", "pasteleria");
    Lead lead = persistLead("pasteleria", "Ciudad de la Costa");

    String context = leadAgentService.buildContext(lead);

    // Con un proveedor real matcheando, el LLM no puede recibir la
    // instrucción de decir que no hay proveedores.
    assertThat(context).doesNotContain("no hay proveedores libres");
    assertThat(context).contains("Proveedores disponibles en esa zona+servicio: 1");
  }

  @Test
  void contextNeverClaimsAvailabilityWhenCategoryOrZoneUndefined() {
    Lead lead = persistLead(null, null);

    String context = leadAgentService.buildContext(lead);

    assertThat(context).doesNotContain("no hay proveedores libres");
    assertThat(context).contains("NO afirmes nada sobre disponibilidad");
  }

  @Test
  void contextStillWarnsWhenGenuinelyNoProvidersForDefinedRequest() {
    // Categoría y zona definidas (ambas dentro del MVP), pero sin ningún
    // proveedor que matchee: ahí sí corresponde el aviso honesto de "no hay
    // proveedores libres".
    Lead lead = persistLead("barometrica", "Aeroparque");

    String context = leadAgentService.buildContext(lead);

    assertThat(context).contains("no hay proveedores libres");
  }
}
