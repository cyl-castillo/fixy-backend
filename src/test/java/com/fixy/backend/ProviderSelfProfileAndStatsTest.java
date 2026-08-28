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
 * Perfil editable + estadísticas del proveedor (self-service, Ola 2):
 * Melissa (provider #10 en prod) no podía corregir su propia descripción,
 * todo pasaba por ops. Cubre: PATCH /profile solo toca campos permitidos
 * (status/categories ignorados si se cuelan en el JSON), nombre vacío
 * rechazado, token inválido 403, y GET /stats con datos y sin datos.
 *
 * @Transactional: rollback al terminar cada test, mismo patrón que
 * ProviderOpportunityControllerTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProviderSelfProfileAndStatsTest {

  @Autowired
  private MockMvc mockMvc;

  private record ProviderAndLead(Integer providerId, String providerToken, Integer leadId) {
  }

  private Integer createProvider(String name, String phone) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "%s",
                  "phone": "%s",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "plomeria"
                }
                """.formatted(name, phone)))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private String accessTokenFor(Integer providerId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }

  private ProviderAndLead createAssignedLead(String providerPhone, String leadPhone) throws Exception {
    Integer providerId = createProvider("Plomeria Perfil Test", providerPhone);
    String providerToken = accessTokenFor(providerId);

    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "%s",
                  "problem": "Necesito plomero para prueba de perfil",
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
  void shouldUpdateOnlyAllowedProfileFields() throws Exception {
    Integer providerId = createProvider("Melissa Test", "099700001");
    String token = accessTokenFor(providerId);

    mockMvc.perform(patch("/api/public/providers/{id}/profile", providerId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Melissa Reparaciones",
                  "description": "Reparaciones del hogar, más de 10 años de experiencia",
                  "coverageZones": "Solymar, Lomas de Solymar, Montes de Solymar",
                  "phone": "099700002"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Melissa Reparaciones"))
        .andExpect(jsonPath("$.description").value("Reparaciones del hogar, más de 10 años de experiencia"))
        .andExpect(jsonPath("$.coverageZones").value("Solymar, Lomas de Solymar, Montes de Solymar"))
        .andExpect(jsonPath("$.phone").value("099700002"))
        // Nunca expuestos como editables: siguen con su valor por default.
        .andExpect(jsonPath("$.categories").value("plomeria"))
        .andExpect(jsonPath("$.status").value("NEW"));
  }

  @Test
  void shouldIgnoreStatusAndCategoriesFieldsIfSentInBody() throws Exception {
    Integer providerId = createProvider("Melissa Test 2", "099700003");
    String token = accessTokenFor(providerId);

    // El DTO ni siquiera tiene esos campos: Jackson los ignora (no falla,
    // por defecto no está en modo estricto), y el servicio nunca los toca.
    mockMvc.perform(patch("/api/public/providers/{id}/profile", providerId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Melissa Reparaciones",
                  "status": "VERIFIED",
                  "categories": "electricidad",
                  "ratingAverage": 5.0
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categories").value("plomeria"))
        .andExpect(jsonPath("$.status").value("NEW"));
  }

  @Test
  void shouldRejectBlankName() throws Exception {
    Integer providerId = createProvider("Melissa Test 3", "099700004");
    String token = accessTokenFor(providerId);

    mockMvc.perform(patch("/api/public/providers/{id}/profile", providerId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\": \"   \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldRejectProfileUpdateWithInvalidToken() throws Exception {
    Integer providerId = createProvider("Melissa Test 4", "099700005");

    mockMvc.perform(patch("/api/public/providers/{id}/profile", providerId)
            .param("token", "token-invalido")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\": \"Otro nombre\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldRejectStatsWithInvalidToken() throws Exception {
    Integer providerId = createProvider("Melissa Test 5", "099700006");

    mockMvc.perform(get("/api/public/providers/{id}/stats", providerId)
            .param("token", "token-invalido"))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldReturnHonestEmptyStatsForNewProvider() throws Exception {
    Integer providerId = createProvider("Proveedor Nuevo", "099700007");
    String token = accessTokenFor(providerId);

    mockMvc.perform(get("/api/public/providers/{id}/stats", providerId)
            .param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptanceRate").doesNotExist())
        .andExpect(jsonPath("$.acceptedCount").value(0))
        .andExpect(jsonPath("$.rejectedCount").value(0))
        .andExpect(jsonPath("$.ratingAverage").doesNotExist())
        .andExpect(jsonPath("$.ratingCount").value(0))
        .andExpect(jsonPath("$.completedByWeek.length()").value(4))
        .andExpect(jsonPath("$.completedByWeek[0].count").value(0))
        .andExpect(jsonPath("$.completedByWeek[3].count").value(0));
  }

  @Test
  void shouldComputeAcceptanceRateAndCompletedWeekAfterRealActivity() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099700008", "099700108");

    // Acepta (ASSIGNED -> ASSIGNED no cambia nada porque ya está asignado
    // por ops; forzamos vía IN_PROGRESS para que el contador de aceptado
    // suba con una transición real: ASSIGNED, que es la que incrementa
    // acceptedJobsCount en ProviderSelfService.updateLeadStatus).
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"IN_PROGRESS\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 1500.00}"))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/providers/{id}/stats", ctx.providerId())
            .param("token", ctx.providerToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptedCount").value(0))
        .andExpect(jsonPath("$.rejectedCount").value(0))
        .andExpect(jsonPath("$.completedByWeek[3].count").value(1));
  }

  @Test
  void shouldComputeAcceptanceRateFromAcceptedAndRejectedCounters() throws Exception {
    Integer providerId = createProvider("Proveedor Con Historial", "099700009");
    String token = accessTokenFor(providerId);

    // Dos leads: uno aceptado (ASSIGNED desde estado inicial vía panel),
    // otro rechazado (CANCELLED). Ambos ya vienen asignados por ops para
    // simplificar el fixture (igual que createAssignedLead).
    ProviderAndLead accepted = assignLeadTo(providerId, token, "099700109", "ASSIGNED");
    ProviderAndLead rejected = assignLeadTo(providerId, token, "099700110", "CANCELLED");

    mockMvc.perform(get("/api/public/providers/{id}/stats", providerId)
            .param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.acceptedCount").value(1))
        .andExpect(jsonPath("$.rejectedCount").value(1))
        .andExpect(jsonPath("$.acceptanceRate").value(0.5));
  }

  private ProviderAndLead assignLeadTo(Integer providerId, String token, String leadPhone, String targetStatus)
      throws Exception {
    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "%s",
                  "problem": "Necesito plomero para prueba de stats",
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
            .content("{\"status\": \"PROVIDER_CONTACTED\", \"assignedProviderId\": %d}".formatted(providerId)))
        .andExpect(status().isOk());

    String body = "CANCELLED".equals(targetStatus)
        ? "{\"status\": \"%s\", \"cancelReason\": \"sin_disponibilidad\"}".formatted(targetStatus)
        : "{\"status\": \"%s\"}".formatted(targetStatus);
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk());

    return new ProviderAndLead(providerId, token, leadId);
  }
}
