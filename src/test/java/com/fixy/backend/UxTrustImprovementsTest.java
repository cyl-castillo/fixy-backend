package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadRating;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadAgentService;
import com.fixy.backend.service.LeadMessageService;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tanda UX 2026-08 "confianza + humanidad" (investigación de mercado):
 * (1) el preview público lleva reseñas con texto; (2) la ficha del
 * proveedor asignado lleva teléfono (botón Llamar) y reseñas; (3) el
 * cliente puede pedir ayuda humana con un toque.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UxTrustImprovementsTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadRatingRepository leadRatingRepository;
  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;

  private Provider createProviderWithReview(String zone) {
    Provider provider = new Provider();
    provider.setName("Electro UX Test");
    provider.setPhone("099444555");
    provider.setCategories("electricidad");
    provider.setPrimaryZone(zone);
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setRatingAverage(5.0);
    provider.setRatingCount(1);
    provider = providerRepository.save(provider);

    LeadRating rating = new LeadRating();
    rating.setLeadId(999L);
    rating.setProviderId(provider.getId());
    rating.setScore(5);
    rating.setComment("Excelente trabajo, llegó puntual y dejó todo impecable");
    leadRatingRepository.save(rating);
    return provider;
  }

  @Test
  void elPreviewPublicoIncluyeResenasConTexto() throws Exception {
    createProviderWithReview("Zona Preview UX");

    MvcResult res = mockMvc.perform(get("/api/public/providers/preview")
            .param("category", "electricidad")
            .param("zone", "Zona Preview UX"))
        .andExpect(status().isOk())
        .andReturn();
    String body = res.getResponse().getContentAsString();
    List<String> comments = JsonPath.read(body, "$.sample[0].recentReviews[*].comment");
    assertThat(comments).anyMatch(c -> c.contains("Excelente trabajo"));
  }

  @Test
  void laFichaDelAsignadoLlevaTelefonoYResenas() throws Exception {
    Provider provider = createProviderWithReview("Zona Ficha UX");
    Lead lead = new Lead();
    lead.setProblem("Se cortó la luz del tablero");
    lead.setDetectedCategory("electricidad");
    lead.setLocation("Zona Ficha UX");
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    lead.setAccessToken("token-ficha-ux");
    lead = leadRepository.save(lead);

    MvcResult res = mockMvc.perform(get("/api/public/leads/{id}", lead.getId())
            .param("token", "token-ficha-ux"))
        .andExpect(status().isOk())
        .andReturn();
    String body = res.getResponse().getContentAsString();
    assertThat((String) JsonPath.read(body, "$.assignedProviderSummary.phone")).isEqualTo("099444555");
    List<String> comments = JsonPath.read(body, "$.assignedProviderSummary.recentReviews[*].comment");
    assertThat(comments).isNotEmpty();
  }

  @Test
  void pedirAyudaHumanaConfirmaUnaVezYNoSpamea() throws Exception {
    Lead lead = new Lead();
    lead.setProblem("Necesito ayuda con el pedido");
    lead.setStatus(LeadStatus.NEW);
    lead.setAccessToken("token-ayuda-ux");
    lead = leadRepository.save(lead);

    mockMvc.perform(post("/api/public/leads/{id}/human-help", lead.getId())
            .param("token", "token-ayuda-ux"))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/public/leads/{id}/human-help", lead.getId())
            .param("token", "token-ayuda-ux"))
        .andExpect(status().isOk());

    long confirmations = leadMessageService.recentForAgent(lead.getId(), 20).stream()
        .filter(m -> m.getText() != null && m.getText().contains("le avisé a una persona del equipo"))
        .count();
    assertThat(confirmations).as("la confirmación se postea UNA vez aunque toque varias").isEqualTo(1);
  }

  @Test
  void ayudaHumanaConTokenInvalidoDa403() throws Exception {
    Lead lead = new Lead();
    lead.setProblem("x");
    lead.setStatus(LeadStatus.NEW);
    lead.setAccessToken("token-real");
    lead = leadRepository.save(lead);
    mockMvc.perform(post("/api/public/leads/{id}/human-help", lead.getId())
            .param("token", "token-falso"))
        .andExpect(status().isForbidden());
  }
}
