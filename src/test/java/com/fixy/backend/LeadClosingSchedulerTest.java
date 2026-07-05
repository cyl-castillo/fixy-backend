package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.LeadClosingScheduler;
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
 * H2.4: auto-confirmación NEUTRAL a las N horas sin respuesta del
 * cliente. auto-confirm-hours=0 fuerza que cualquier lead COMPLETED ya
 * cumpla el umbral en el momento de correr el job — evita mockear Clock
 * para el camino feliz. El caso "no dispara antes de tiempo" usa el
 * default de producción (72h), que un test que corre en milisegundos
 * jamás alcanza.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.enabled=false",
    "fixy.closing.enabled=true",
    "fixy.closing.auto-confirm-hours=0"
})
class LeadClosingSchedulerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private LeadClosingScheduler scheduler;

  @Autowired
  private LeadRepository leadRepository;

  private Integer createCompletedLead(String providerPhone, String leadPhone) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Gasista Auto Confirm Test",
                  "phone": "%s",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "gas"
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
                  "problem": "Necesito gasista para prueba de auto-confirm",
                  "channel": "web-app",
                  "serviceCategory": "gas",
                  "zone": "Solymar"
                }
                """.formatted(leadPhone)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");

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

    return leadId;
  }

  @Test
  void shouldAutoConfirmCompletedLeadWithoutRatingOrDispute() throws Exception {
    Integer leadId = createCompletedLead("099800200", "099800201");

    int processed = scheduler.processOnce();

    assertThat(processed).isEqualTo(1);
    Lead lead = leadRepository.findById(leadId.longValue()).orElseThrow();
    assertThat(lead.getClosingAutoConfirmedAt()).isNotNull();
    assertThat(lead.isDisputed()).isFalse();

    // Idempotente: correr el job de nuevo no debe re-emitir ni re-procesar.
    int processedAgain = scheduler.processOnce();
    assertThat(processedAgain).isEqualTo(0);
  }

  @Test
  void shouldNotAutoConfirmIfAlreadyRated() throws Exception {
    Integer leadId = createCompletedLead("099800210", "099800211");
    // Confirmación real del cliente: leemos el accessToken directo del
    // repo (más simple que ir por HTTP solo para obtenerlo).
    Lead lead = leadRepository.findById(leadId.longValue()).orElseThrow();
    String leadToken = lead.getAccessToken();

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", leadId)
            .param("token", leadToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 5}"))
        .andExpect(status().isOk());

    int processed = scheduler.processOnce();

    assertThat(processed).isEqualTo(0);
    Lead reloaded = leadRepository.findById(leadId.longValue()).orElseThrow();
    assertThat(reloaded.getClosingAutoConfirmedAt()).isNull();
  }

  @Test
  void shouldNotAutoConfirmIfDisputed() throws Exception {
    Integer leadId = createCompletedLead("099800220", "099800221");
    Lead lead = leadRepository.findById(leadId.longValue()).orElseThrow();
    String leadToken = lead.getAccessToken();

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", leadId)
            .param("token", leadToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": false, \"comment\": \"problema\"}"))
        .andExpect(status().isOk());

    int processed = scheduler.processOnce();

    assertThat(processed).isEqualTo(0);
    Lead reloaded = leadRepository.findById(leadId.longValue()).orElseThrow();
    assertThat(reloaded.getClosingAutoConfirmedAt()).isNull();
    assertThat(reloaded.isDisputed()).isTrue();
  }
}
