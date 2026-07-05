package com.fixy.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * P0-2: H2.1 (confirmación pública), H2.3 (agregación de rating) y H2.6
 * (disputa). Corre con fixy.payments.enabled=false (a diferencia de la
 * suite general) para aislar el loop de cierre de la comisión de P0-1 —
 * ambos flujos son independientes (la comisión no se auto-reversa por
 * disputa, según decisión de Carlos) y mezclarlos solo agregaría ruido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fixy.payments.enabled=false")
class LeadClosingServiceTest {

  @Autowired
  private MockMvc mockMvc;

  private record ProviderAndLead(
      Integer providerId, String providerToken, Integer leadId, String leadToken
  ) {
  }

  private ProviderAndLead createCompletedLead(String providerPhone, String leadPhone) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Electricista Rating Test",
                  "phone": "%s",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "electricidad"
                }
                """.formatted(providerPhone)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");

    MvcResult tk = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String providerToken = JsonPath.read(tk.getResponse().getContentAsString(), "$.accessToken");

    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "%s",
                  "problem": "Necesito electricista para prueba de rating",
                  "channel": "web-app",
                  "serviceCategory": "electricidad",
                  "zone": "Solymar"
                }
                """.formatted(leadPhone)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
    String leadToken = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(patch("/api/leads/{id}", leadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"ASSIGNED\", \"assignedProviderId\": %d}".formatted(providerId)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    return new ProviderAndLead(providerId, providerToken, leadId, leadToken);
  }

  @Test
  void shouldRejectConfirmationWithInvalidToken() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099800001", "099800101");

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", "token-que-no-es")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 5}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldRejectConfirmationWithProviderToken() throws Exception {
    // El proveedor NO puede confirmar en nombre del cliente: el endpoint
    // exige el token del LEAD, no el del proveedor.
    ProviderAndLead ctx = createCompletedLead("099800002", "099800102");

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 5}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldRejectScoreOutsideOneToFive() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099800003", "099800103");

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 6}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 0}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldRejectMissingScoreWhenConfirmed() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099800004", "099800104");

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldRejectConfirmationBeforeCompleted() throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Electricista No Completado",
                  "phone": "099800005",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "electricidad"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");

    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099800105",
                  "problem": "Lead todavia no completado",
                  "channel": "web-app",
                  "serviceCategory": "electricidad",
                  "zone": "Solymar"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
    String leadToken = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(patch("/api/leads/{id}", leadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"ASSIGNED\", \"assignedProviderId\": %d}".formatted(providerId)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", leadId)
            .param("token", leadToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 5}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldConfirmWithRatingAndAverageThreeRatingsExactly() throws Exception {
    // Un solo proveedor, 3 leads completados, 3 ratings distintos: 5, 4, 3.
    // Promedio esperado: 4.00 exacto (criterio del roadmap).
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Electricista Promedio Test",
                  "phone": "099800010",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "electricidad"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");

    MvcResult tk = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String providerToken = JsonPath.read(tk.getResponse().getContentAsString(), "$.accessToken");

    int[] scores = {5, 4, 3};
    for (int i = 0; i < scores.length; i++) {
      String leadPhone = "09980011" + i;
      MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  {
                    "phone": "%s",
                    "problem": "Necesito electricista para prueba de promedio",
                    "channel": "web-app",
                    "serviceCategory": "electricidad",
                    "zone": "Solymar"
                  }
                  """.formatted(leadPhone)))
          .andExpect(status().isCreated())
          .andReturn();
      Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
      String leadToken = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.accessToken");

      mockMvc.perform(patch("/api/leads/{id}", leadId)
              .with(httpBasic("test-ops", "test-pass"))
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"status\": \"ASSIGNED\", \"assignedProviderId\": %d}".formatted(providerId)))
          .andExpect(status().isOk());

      mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
              .param("token", providerToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"status\": \"COMPLETED\"}"))
          .andExpect(status().isOk());

      mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", leadId)
              .param("token", leadToken)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"confirmed\": true, \"score\": %d, \"comment\": \"anduvo bien\"}".formatted(scores[i])))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.confirmed").value(true))
          .andExpect(jsonPath("$.disputed").value(false))
          .andExpect(jsonPath("$.score").value(scores[i]));
    }

    mockMvc.perform(get("/api/providers")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == %d)].ratingAverage".formatted(providerId)).value(Matchers.hasItem(4.0)))
        .andExpect(jsonPath("$[?(@.id == %d)].ratingCount".formatted(providerId)).value(Matchers.hasItem(3)));
  }

  @Test
  void shouldRejectDoubleSubmit() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099800020", "099800120");

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 5}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 4}"))
        .andExpect(status().isConflict());
  }

  @Test
  void shouldMarkDisputedAndExposeFlagToOps() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099800030", "099800130");

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": false, \"comment\": \"no vino\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confirmed").value(false))
        .andExpect(jsonPath("$.disputed").value(true));

    mockMvc.perform(get("/api/leads")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == %d)].disputed".formatted(ctx.leadId())).value(Matchers.hasItem(true)));

    // Doble submit tras disputa también se rechaza.
    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 5}"))
        .andExpect(status().isConflict());
  }
}
