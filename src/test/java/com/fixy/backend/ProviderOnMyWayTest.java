package com.fixy.backend;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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

import com.fixy.backend.service.PushNotificationService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * "Voy en camino" (caso real Nueva Era, lead #105: "en 40 min maso llega"
 * quedó como texto perdido en el chat — esto lo convierte en botón). Cubre:
 * evento de timeline + mensaje al chat + push (mock), anti-spam 1h, token
 * inválido 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderOnMyWayTest {

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
                  "name": "Barométrica Nueva Era",
                  "phone": "%s",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "barometrica"
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
                  "problem": "Necesito barométrica para prueba de voy en camino",
                  "channel": "web-app",
                  "serviceCategory": "barometrica",
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

  @Test
  void onMyWay_postsTimelineEventMessageAndPush() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099700001", "099700101");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/on-my-way", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"etaMinutes\": 40}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ctx.leadId()));

    // (a) evento de timeline PROVIDER_ON_THE_WAY, visible por el token del cliente.
    mockMvc.perform(get("/api/public/leads/{id}/timeline", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type=='PROVIDER_ON_THE_WAY')]").isNotEmpty());

    // (b) mensaje en el chat, texto literal con nombre del proveedor y ETA.
    mockMvc.perform(get("/api/public/leads/{id}/messages", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.text=='🚛 Barométrica Nueva Era avisó que va en camino — llega en ~40 min')]")
            .isNotEmpty());

    // (c) push al cliente.
    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
        .untilAsserted(() -> verify(pushNotificationService, times(1))
            .notifyLeadHasNews(eq(ctx.leadId().longValue()), contains("va en camino"),
                contains("Barométrica Nueva Era")));
  }

  @Test
  void onMyWay_withoutEta_omitsEtaSuffix() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099700002", "099700102");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/on-my-way", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken()))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/leads/{id}/messages", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.text=='🚛 Barométrica Nueva Era avisó que va en camino')]").isNotEmpty());
  }

  @Test
  void onMyWay_secondCallWithinAnHour_isRejectedWithTooManyRequests() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099700003", "099700103");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/on-my-way", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken()))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/on-my-way", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken()))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void onMyWay_afterCooldownExpires_isAllowedAgain() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099700004", "099700104");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/on-my-way", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken()))
        .andExpect(status().isOk());

    // Simulamos que pasó más de una hora retrocediendo el timestamp del
    // evento ya persistido (createdAt es @PrePersist / updatable=false en
    // LeadEvent, sin setter — mismo patrón JdbcTemplate que
    // ProviderClosingReminderSchedulerTest para envejecer eventos en tests).
    jdbcTemplate.update(
        "UPDATE lead_events SET created_at = ? WHERE lead_id = ? AND type = 'PROVIDER_ON_THE_WAY'",
        OffsetDateTime.now().minusHours(2), ctx.leadId().longValue());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/on-my-way", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken()))
        .andExpect(status().isOk());
  }

  @Test
  void onMyWay_invalidToken_isForbidden() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099700005", "099700105");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/on-my-way", ctx.providerId(), ctx.leadId())
            .param("token", "token-robado"))
        .andExpect(status().isForbidden());

    verify(pushNotificationService, never()).notifyLeadHasNews(eq(ctx.leadId().longValue()), anyString(), anyString());
  }

  @Test
  void onMyWay_leadNotAssignedToProvider_isForbidden() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099700006", "099700106");

    MvcResult otherProv = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Otro Proveedor",
                  "phone": "099700200",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "barometrica"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer otherProviderId = JsonPath.read(otherProv.getResponse().getContentAsString(), "$.id");
    MvcResult tk = mockMvc.perform(post("/api/providers/{id}/access-token", otherProviderId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String otherToken = JsonPath.read(tk.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/on-my-way", otherProviderId, ctx.leadId())
            .param("token", otherToken))
        .andExpect(status().isForbidden());
  }
}
