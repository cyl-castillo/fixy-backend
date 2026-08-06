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
import org.springframework.transaction.annotation.Transactional;

/**
 * Agenda/disponibilidad MVP del proveedor (base de Ola 2): un booleano
 * {@code acceptingWork} que el proveedor setea desde su panel con el mismo
 * token de self-service. Cubre: default disponible (no rompe proveedores
 * existentes como Melissa), el proveedor puede pausarse y reactivarse, y en
 * pausa desaparece de la bandeja de oportunidades sin afectar trabajos ya
 * asignados.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProviderAvailabilityEndpointTest {

  @Autowired
  private MockMvc mockMvc;

  private Integer createProvider(String name, String phone, String zone, String category) throws Exception {
    String payload = """
        {
          "name": "%s",
          "phone": "%s",
          "primaryZone": "%s",
          "city": "Ciudad de la Costa",
          "categories": "%s"
        }
        """.formatted(name, phone, zone, category);
    MvcResult result = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    // El alta de ops nace NEW y desde el 2026-08-06 NEW no recibe trabajo:
    // este fixture necesita un proveedor aprobado, así que lo aprueba igual
    // que Carlos en el admin. La pausa (lo que este test mide) es un flag
    // aparte y sigue siendo del proveedor.
    mockMvc.perform(patch("/api/providers/{id}", providerId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"AVAILABLE\"}"))
        .andExpect(status().isOk());
    return providerId;
  }

  private String accessTokenFor(Integer providerId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }

  private Integer createReadyLead(String phone, String problem, String category, String zone, String urgency)
      throws Exception {
    String payload = """
        {
          "name": "Cliente Test",
          "phone": "%s",
          "problem": "%s",
          "channel": "web-app",
          "serviceCategory": "%s",
          "zone": "%s",
          "urgency": "%s"
        }
        """.formatted(phone, problem, category, zone, urgency);
    MvcResult result = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.readyForMatching").value(true))
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  @Test
  void defaultsToAcceptingWorkForExistingAndNewProviders() throws Exception {
    Integer providerId = createProvider("Plomeros Default", "099200000", "Solymar", "plomeria");
    String token = accessTokenFor(providerId);

    mockMvc.perform(get("/api/public/providers/{id}/me", providerId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptingWork").value(true));
  }

  @Test
  void providerCanPauseAndResumeViaAvailabilityEndpoint() throws Exception {
    Integer providerId = createProvider("Plomeros Pausa", "099200001", "Solymar", "plomeria");
    String token = accessTokenFor(providerId);

    mockMvc.perform(patch("/api/public/providers/{id}/availability", providerId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptingWork\": false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptingWork").value(false));

    mockMvc.perform(get("/api/public/providers/{id}/me", providerId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptingWork").value(false));

    mockMvc.perform(patch("/api/public/providers/{id}/availability", providerId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptingWork\": true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptingWork").value(true));
  }

  @Test
  void rejectsAvailabilityUpdateWithInvalidToken() throws Exception {
    Integer providerId = createProvider("Plomeros Token Malo", "099200002", "Solymar", "plomeria");

    mockMvc.perform(patch("/api/public/providers/{id}/availability", providerId)
            .param("token", "wrong-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptingWork\": false}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void pausedProviderDoesNotReceiveNewOpportunitiesButKeepsAssignedLeads() throws Exception {
    Integer providerId = createProvider("Plomeros En Pausa", "099200003", "Solymar", "plomeria");
    String token = accessTokenFor(providerId);

    // Lead ya asignado antes de pausar: debe seguir visible en /me tras la pausa.
    Integer assignedLeadId = createReadyLead("099200103", "Perdida de agua ya asignada",
        "plomeria", "Solymar", "media");
    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/accept", providerId, assignedLeadId)
            .param("token", token))
        .andExpect(status().isOk());

    mockMvc.perform(patch("/api/public/providers/{id}/availability", providerId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"acceptingWork\": false}"))
        .andExpect(status().isOk());

    // Nueva oportunidad creada DESPUÉS de la pausa: no debe aparecer.
    createReadyLead("099200104", "Perdida de agua nueva mientras esta en pausa",
        "plomeria", "Solymar", "alta");

    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // El trabajo ya asignado sigue intacto en /me.
    mockMvc.perform(get("/api/public/providers/{id}/me", providerId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedLeads.length()").value(1))
        .andExpect(jsonPath("$.assignedLeads[0].id").value(assignedLeadId));
  }
}
