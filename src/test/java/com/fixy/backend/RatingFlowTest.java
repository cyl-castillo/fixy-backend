package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.TelegramNotifyService;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tier 2 (contrato §C.3): rating propio del lead, detractores escalados a
 * un humano, respuesta pública del proveedor.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fixy.payments.provider-commission-enabled=false")
class RatingFlowTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;

  @MockitoBean private TelegramNotifyService telegramNotifyService;

  private record ProviderAndLead(Integer providerId, String providerToken, Integer leadId, String leadToken) {
  }

  private ProviderAndLead createCompletedLead(String providerPhone, String leadPhone) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Tecnico Rating Test", "phone": "%s", "primaryZone": "Solymar",
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
                {"phone": "%s", "problem": "Perdida de gas test rating", "channel": "web-app",
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
  void enviaUnaReseñaPropiaYQuedaVisibleEnElLead() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099860001", "099860101");

    mockMvc.perform(post("/api/public/leads/{id}/rating", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\": 5, \"comment\": \"Excelente atención\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.score").value(5))
        .andExpect(jsonPath("$.verified").value(false));

    mockMvc.perform(get("/api/public/leads/{id}", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rating.score").value(5))
        .andExpect(jsonPath("$.rating.comment").value("Excelente atención"));

    verify(telegramNotifyService, times(1)).notifyRatingSubmitted(
        argThat(l -> l.getId().equals(ctx.leadId().longValue())), argThat(r -> r.getScore() == 5));
    verify(telegramNotifyService, never()).notifyLowRating(org.mockito.ArgumentMatchers.any(), anyInt(), anyString());
  }

  @Test
  void noSePuedeCalificarDosVeces() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099860002", "099860102");

    mockMvc.perform(post("/api/public/leads/{id}/rating", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\": 4}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/leads/{id}/rating", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\": 3}"))
        .andExpect(status().isConflict());
  }

  @Test
  void detractorEscalaAUnHumanoYAvisaAlVecino() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099860003", "099860103");

    mockMvc.perform(post("/api/public/leads/{id}/rating", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\": 2, \"comment\": \"no vino a horario\"}"))
        .andExpect(status().isOk());

    verify(telegramNotifyService, times(1)).notifyLowRating(
        argThat(l -> l.getId().equals(ctx.leadId().longValue())), eq(2), eq("no vino a horario"));

    List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(ctx.leadId().longValue());
    assertThat(messages).extracting(LeadMessage::getText)
        .anyMatch(t -> t.contains("Gracias por contarlo con franqueza"));

    // Nunca se modifica ni oculta el score.
    Lead lead = leadRepository.findById(ctx.leadId().longValue()).orElseThrow();
    mockMvc.perform(get("/api/public/leads/{id}", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(jsonPath("$.rating.score").value(2));
  }

  @Test
  void elProveedorPuedeResponderUnaSolaVez() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099860004", "099860104");

    mockMvc.perform(post("/api/public/leads/{id}/rating", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\": 5, \"comment\": \"todo joya\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/rating-reply", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"Gracias por confiar en nosotros!\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providerReply").value("Gracias por confiar en nosotros!"));

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/rating-reply", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"otra respuesta\"}"))
        .andExpect(status().isConflict());

    mockMvc.perform(get("/api/public/leads/{id}", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(jsonPath("$.rating.providerReply").value("Gracias por confiar en nosotros!"));

    List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(ctx.leadId().longValue());
    assertThat(messages).extracting(LeadMessage::getText)
        .anyMatch(t -> t.contains("respondió a tu reseña"));
  }

  @Test
  void unProveedorAjenoNoPuedeResponderLaReseña() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099860005", "099860105");
    mockMvc.perform(post("/api/public/leads/{id}/rating", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"score\": 5}"))
        .andExpect(status().isOk());

    MvcResult ajenoResult = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Tecnico Ajeno Rating", "phone": "099860999", "primaryZone": "Solymar",
                 "city": "Ciudad de la Costa", "categories": "gas"}
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer ajenoId = JsonPath.read(ajenoResult.getResponse().getContentAsString(), "$.id");
    MvcResult ajenoTk = mockMvc.perform(post("/api/providers/{id}/access-token", ajenoId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String ajenoToken = JsonPath.read(ajenoTk.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/rating-reply", ajenoId, ctx.leadId())
            .param("token", ajenoToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"no debería poder\"}"))
        .andExpect(status().isForbidden());
  }
}
