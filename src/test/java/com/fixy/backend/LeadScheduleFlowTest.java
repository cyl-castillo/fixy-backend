package com.fixy.backend;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.service.PushNotificationService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * "Horario acordado con un toque": el proveedor propone día/franja desde su
 * panel y el cliente confirma/rechaza sin tipear. Cubre el trío evento +
 * mensaje + push en ambas direcciones, la idempotencia de la respuesta
 * (doble toque → 409), y que una propuesta nueva reemplaza a la anterior.
 * Mismo esqueleto que ProviderOnMyWayTest (su gemelo de flujo).
 */
@SpringBootTest
@AutoConfigureMockMvc
class LeadScheduleFlowTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @MockitoBean
  private PushNotificationService pushNotificationService;

  private record ProviderAndLead(Integer providerId, String providerToken, Integer leadId, String leadToken) {
  }

  private ProviderAndLead createAssignedLead(String providerPhone, String leadPhone) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Plomera Agenda Test",
                  "phone": "%s",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "plomeria"
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
                  "problem": "Pérdida de agua para prueba de agenda",
                  "channel": "web-app",
                  "serviceCategory": "plomeria",
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

    return new ProviderAndLead(providerId, providerToken, leadId, leadToken);
  }

  private void propose(ProviderAndLead ctx, String proposal) throws Exception {
    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/schedule-proposal", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"proposal\": \"%s\"}".formatted(proposal)))
        .andExpect(status().isOk());
  }

  private MvcResult respond(ProviderAndLead ctx, boolean accept) throws Exception {
    return mockMvc.perform(post("/api/public/leads/{id}/schedule-response", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accept\": %s}".formatted(accept)))
        .andReturn();
  }

  @Test
  void proposeSchedule_postsEventMessageAndPushesCustomer() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099710001", "099710101");

    propose(ctx, "mañana de 14 a 16");

    mockMvc.perform(get("/api/public/leads/{id}/timeline", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type=='SCHEDULE_PROPOSED' && @.message=='mañana de 14 a 16')]").isNotEmpty());

    mockMvc.perform(get("/api/public/leads/{id}/messages", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$[?(@.text=='📅 Plomera Agenda Test propone pasar mañana de 14 a 16. Si te sirve, confirmalo acá en el chat con un toque.')]")
            .isNotEmpty());

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> Mockito.verify(pushNotificationService, Mockito.atLeastOnce())
            .notifyLeadHasNews(eq(ctx.leadId().longValue()), contains("propuso un horario"), contains("mañana de 14 a 16")));
  }

  @Test
  void customerConfirms_writesEventMessageAndPushesProvider() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099710002", "099710102");
    propose(ctx, "hoy de tarde (14 a 18)");

    MvcResult res = respond(ctx, true);
    org.assertj.core.api.Assertions.assertThat(res.getResponse().getStatus()).isEqualTo(200);
    org.assertj.core.api.Assertions.assertThat(res.getResponse().getContentAsString())
        .contains("\"status\":\"confirmed\"");

    mockMvc.perform(get("/api/public/leads/{id}/timeline", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type=='SCHEDULE_CONFIRMED' && @.message=='hoy de tarde (14 a 18)')]").isNotEmpty());

    mockMvc.perform(get("/api/public/leads/{id}/messages", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.text=='✅ Horario confirmado: hoy de tarde (14 a 18).')]").isNotEmpty());

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> Mockito.verify(pushNotificationService, Mockito.times(1))
            .notifyProvider(eq(ctx.providerId().longValue()), Mockito.anyString(),
                contains("confirmó el horario"), contains("hoy de tarde (14 a 18)")));
  }

  @Test
  void customerRejects_thenNewProposalCanBeConfirmed() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099710003", "099710103");
    propose(ctx, "hoy de mañana (9 a 12)");

    MvcResult rejected = respond(ctx, false);
    org.assertj.core.api.Assertions.assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
    org.assertj.core.api.Assertions.assertThat(rejected.getResponse().getContentAsString())
        .contains("\"status\":\"rejected\"");

    mockMvc.perform(get("/api/public/leads/{id}/timeline", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type=='SCHEDULE_REJECTED')]").isNotEmpty());

    // Anti-doble-toque de propuestas: envejecemos la anterior para poder
    // proponer de nuevo ya (mismo patrón JdbcTemplate que ProviderOnMyWayTest).
    jdbcTemplate.update(
        "UPDATE lead_events SET created_at = ? WHERE lead_id = ? AND type = 'SCHEDULE_PROPOSED'",
        OffsetDateTime.now().minusMinutes(5), ctx.leadId().longValue());

    propose(ctx, "mañana de 9 a 12");
    MvcResult confirmed = respond(ctx, true);
    org.assertj.core.api.Assertions.assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
    org.assertj.core.api.Assertions.assertThat(confirmed.getResponse().getContentAsString())
        .contains("mañana de 9 a 12");
  }

  @Test
  void doubleResponse_isConflict() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099710004", "099710104");
    propose(ctx, "mañana de 14 a 16");

    respond(ctx, true);
    MvcResult second = respond(ctx, true);
    org.assertj.core.api.Assertions.assertThat(second.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void respondWithoutProposal_isConflict() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099710005", "099710105");

    MvcResult res = respond(ctx, true);
    org.assertj.core.api.Assertions.assertThat(res.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void proposeWithInvalidTokenOrBlankProposal_fails() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099710006", "099710106");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/schedule-proposal", ctx.providerId(), ctx.leadId())
            .param("token", "token-robado")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"proposal\": \"mañana de 14 a 16\"}"))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/schedule-proposal", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"proposal\": \"  \"}"))
        .andExpect(status().isBadRequest());
  }
}
