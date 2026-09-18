package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tier 2 (contrato §C.1): {@code score} pasa a opcional en {@code POST
 * /leads/{id}/confirm-completion} también con {@code confirmed=true} — el
 * caso "con score" mantiene compatibilidad (crea LeadRating y recalcula
 * agregados igual que antes); sin score, confirma sin calificar y deja la
 * reseña para el pedido de C.2/C.3.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fixy.payments.provider-commission-enabled=false")
class LeadCompletionConfirmTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadRatingRepository leadRatingRepository;

  private record ProviderAndLead(Integer providerId, String providerToken, Integer leadId, String leadToken) {
  }

  private ProviderAndLead createCompletedLead(String providerPhone, String leadPhone) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Tecnico Confirm Test", "phone": "%s", "primaryZone": "Solymar",
                 "city": "Ciudad de la Costa", "categories": "gas"}
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
                {"phone": "%s", "problem": "Perdida de gas test confirm", "channel": "web-app",
                 "serviceCategory": "gas", "zone": "Solymar"}
                """.formatted(leadPhone)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
    String leadToken = leadRepository.findById(leadId.longValue()).orElseThrow().getAccessToken();

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

    return new ProviderAndLead(providerId, providerToken, leadId, leadToken);
  }

  @Test
  void confirmarConScoreSigueCreandoElRatingYRecalculandoAgregados() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099870001", "099870101");

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 4, \"comment\": \"bien\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confirmed").value(true))
        .andExpect(jsonPath("$.score").value(4));

    assertThat(leadRatingRepository.existsByLeadId(ctx.leadId().longValue())).isTrue();
    var provider = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/providers/{id}", ctx.providerId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ratingAverage").value(4.0))
        .andExpect(jsonPath("$.ratingCount").value(1));
  }

  @Test
  void confirmarSinScoreNoCreaRatingYDejaLaPuertaAbiertaParaCalificarDespues() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099870002", "099870102");

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.score").doesNotExist());

    assertThat(leadRatingRepository.existsByLeadId(ctx.leadId().longValue())).isFalse();

    // Idempotente: un segundo toque (u otro dispositivo) no duplica el
    // evento ni el mensaje — 409, que el front interpreta como 'already-done'.
    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true}"))
        .andExpect(status().isConflict());

    // La puerta de C.3 sigue abierta: puede calificar después con el
    // endpoint dedicado.
    mockMvc.perform(post("/api/public/leads/{id}/rating", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\": 5}"))
        .andExpect(status().isOk());
    assertThat(leadRatingRepository.existsByLeadId(ctx.leadId().longValue())).isTrue();
  }
}
