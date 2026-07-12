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
 * H_B: el proveedor ve sus comisiones (pendiente vs pagada) en su panel
 * self-service, vía GET /api/public/providers/{id}/commissions con el mismo
 * mecanismo de auth por token que el resto de los endpoints self-service.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderCommissionsEndpointTest {

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
                  "name": "Plomeria Comisiones Test",
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
                  "problem": "Necesito plomero para prueba de comisiones",
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
  void proveedorSinComisionesRecibeEmptyStateProlijo() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099800001", "099800101");

    mockMvc.perform(get("/api/public/providers/{id}/commissions", ctx.providerId())
            .param("token", ctx.providerToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingAmount").value(0))
        .andExpect(jsonPath("$.pendingCount").value(0))
        .andExpect(jsonPath("$.paidAmount").value(0))
        .andExpect(jsonPath("$.paidCount").value(0))
        .andExpect(jsonPath("$.currency").value("UYU"));
  }

  @Test
  void proveedorVeComisionPendienteTrasCompletarLead() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099800002", "099800102");

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 3000.00}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/providers/{id}/commissions", ctx.providerId())
            .param("token", ctx.providerToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendingCount").value(1))
        .andExpect(jsonPath("$.paidCount").value(0))
        .andExpect(jsonPath("$.pendingAmount").value(org.hamcrest.Matchers.greaterThan(0.0)));
  }

  @Test
  void tokenInvalidoRechazaAccesoAComisiones() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099800003", "099800103");

    mockMvc.perform(get("/api/public/providers/{id}/commissions", ctx.providerId())
            .param("token", "token-incorrecto"))
        .andExpect(status().isForbidden());
  }
}
