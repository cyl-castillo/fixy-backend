package com.fixy.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /api/public/leads/{id} expone assignedProviderSummary (contrato
 * acordado con el agente que construye la superficie del cliente): null sin
 * proveedor asignado, datos públicos con proveedor asignado, honestidad de
 * reputación (ratingAverage null si ratingCount==0, mismo criterio que
 * ProviderPublicPreview).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssignedProviderSummaryTest {

  @Autowired
  private MockMvc mockMvc;

  private record CreatedLead(Integer id, String token) {
  }

  private CreatedLead createLead(String phone) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "%s",
                  "problem": "Pérdida en el tanque, necesito barométrica",
                  "channel": "web-app",
                  "serviceCategory": "barometrica",
                  "zone": "Solymar"
                }
                """.formatted(phone)))
        .andExpect(status().isCreated())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    return new CreatedLead(JsonPath.read(body, "$.id"), JsonPath.read(body, "$.accessToken"));
  }

  @Test
  void noAssignedProvider_summaryIsNull() throws Exception {
    CreatedLead lead = createLead("099710001");

    mockMvc.perform(get("/api/public/leads/{id}", lead.id()).param("token", lead.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedProviderSummary").doesNotExist());
  }

  @Test
  void assignedProviderWithRatings_summaryHasRealAverage() throws Exception {
    CreatedLead lead = createLead("099710002");

    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Barométrica Nueva Era",
                  "phone": "099710100",
                  "primaryZone": "Ciudad de la Costa",
                  "city": "Ciudad de la Costa",
                  "categories": "barometrica"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(patch("/api/providers/{id}", providerId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"ratingAverage\": 4.8, \"ratingCount\": 3, \"completedJobsCount\": 1}"))
        .andExpect(status().isOk());

    mockMvc.perform(patch("/api/leads/{id}", lead.id())
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"ASSIGNED\", \"assignedProviderId\": %d}".formatted(providerId)))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/leads/{id}", lead.id()).param("token", lead.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedProviderSummary.name").value("Barométrica Nueva Era"))
        .andExpect(jsonPath("$.assignedProviderSummary.ratingAverage").value(4.8))
        .andExpect(jsonPath("$.assignedProviderSummary.ratingCount").value(3))
        .andExpect(jsonPath("$.assignedProviderSummary.completedJobs").value(1))
        .andExpect(jsonPath("$.assignedProviderSummary.primaryZone").value("Ciudad de la Costa"));
  }

  @Test
  void assignedProviderWithoutRatings_averageIsNullNotInflated() throws Exception {
    CreatedLead lead = createLead("099710003");

    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Plomero Nuevo Sin Historial",
                  "phone": "099710101",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "barometrica"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(patch("/api/leads/{id}", lead.id())
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"ASSIGNED\", \"assignedProviderId\": %d}".formatted(providerId)))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/leads/{id}", lead.id()).param("token", lead.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedProviderSummary.name").value("Plomero Nuevo Sin Historial"))
        .andExpect(jsonPath("$.assignedProviderSummary.ratingAverage").doesNotExist())
        .andExpect(jsonPath("$.assignedProviderSummary.ratingCount").value(0));
  }
}
