package com.fixy.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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
 * H1.2: con fixy.payments.enabled=true (default de src/test/resources/application.yml),
 * COMPLETED sin amountCharged debe rechazarse con 400. El caso "flag OFF sigue
 * funcionando igual" se cubre en {@link ProviderSelfServicePaymentsDisabledTest}
 * con su propio contexto Spring (@TestPropertySource distinto).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderSelfServicePaymentsTest {

  @Autowired
  private MockMvc mockMvc;

  private record ProviderAndLead(Integer providerId, String providerToken, Integer leadId) {
  }

  private ProviderAndLead createAssignedLead(String providerPhone, String leadPhone) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Plomeria Pagos Test",
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
                  "problem": "Necesito plomero para prueba de pagos",
                  "channel": "web-app",
                  "serviceCategory": "plomeria",
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

    return new ProviderAndLead(providerId, providerToken, leadId);
  }

  @Test
  void shouldRejectCompletedWithoutAmountWhenPaymentsEnabled() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099600001", "099600101");

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value(
            org.hamcrest.Matchers.containsString("monto cobrado")));
  }

  @Test
  void shouldRejectCompletedWithZeroOrNegativeAmount() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099600002", "099600102");

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 0}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": -50}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldCompleteWithAmountAndCreateCommission() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099600003", "099600103");

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 2500.00}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/ops/payments")
            .with(httpBasic("test-ops", "test-pass"))
            .param("status", "PENDING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.leadId == %d)].commissionAmount".formatted(ctx.leadId()))
            .value(org.hamcrest.Matchers.hasItem(250.0)));
  }
}
