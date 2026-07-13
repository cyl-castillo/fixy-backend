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
 * Bandeja de oportunidades del proveedor (feat/provider-inbox): el gap era
 * que un proveedor solo veía leads YA asignados — no había forma de ver ni
 * tomar leads nuevos sin WhatsApp (no configurado en prod). Cubre: matching
 * category+zone, privacidad del DTO (sin phone/nombre), race de accept
 * (primero gana, segundo 409), decline idempotente, y tokens inválidos.
 *
 * @Transactional: cada test hace rollback al terminar (mismo patrón que
 * MercadoPagoWebhookControllerTest) para no acumular leads/providers de
 * otros métodos de esta clase — varios tests comparten zona "Solymar" y
 * categoría "plomeria" a propósito, para ejercitar el matching real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProviderOpportunityControllerTest {

  @Autowired
  private MockMvc mockMvc;

  private Integer createProvider(String name, String phone, String zone, String coverageZones, String category)
      throws Exception {
    String payload = """
        {
          "name": "%s",
          "phone": "%s",
          "primaryZone": "%s",
          "coverageZones": "%s",
          "city": "Ciudad de la Costa",
          "categories": "%s"
        }
        """.formatted(name, phone, zone, coverageZones, category);
    MvcResult result = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
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


  /** Token público del lead, vía ops (los endpoints públicos por-lead ahora lo exigen). */
  private String leadAccessTokenFor(Integer leadId) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/leads")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    java.util.List<?> tokens = JsonPath.read(result.getResponse().getContentAsString(),
        "$[?(@.id == %d)].accessToken".formatted(leadId));
    return tokens.get(0).toString();
  }

  private Integer createReadyLead(String phone, String problem, String category, String zone, String urgency)
      throws Exception {
    String payload = leadPayload(phone, problem, category, zone, urgency);
    MvcResult result = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.readyForMatching").value(true))
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  /** Variante sin exigir readyForMatching=true, para casos que a propósito
   * quedan fuera de matching (zona o categoría fuera de MVP). */
  private Integer createLead(String phone, String problem, String category, String zone, String urgency)
      throws Exception {
    String payload = leadPayload(phone, problem, category, zone, urgency);
    MvcResult result = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
  }

  private String leadPayload(String phone, String problem, String category, String zone, String urgency) {
    return """
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
  }

  @Test
  void shouldListOnlyOpportunitiesThatMatchCategoryAndZoneWithoutExposingContactData() throws Exception {
    Integer providerId = createProvider("Plomeros Inbox", "099111000", "Solymar", "Solymar, Lagomar", "plomeria");
    String token = accessTokenFor(providerId);

    Integer matching = createReadyLead("099222000", "Perdida de agua en la cocina, urgente",
        "plomeria", "Solymar", "alta");
    // Categoria no matchea (jardineria vs plomeria).
    createReadyLead("099222001", "Necesito cortar el pasto del jardin", "jardineria", "Solymar", "media");
    // Zona no matchea con el proveedor (cubre Solymar/Lagomar, no El Pinar),
    // aunque El Pinar siga dentro del área MVP general.
    createReadyLead("099222002", "Perdida de agua en el baño", "plomeria", "El Pinar", "media");

    MvcResult listResult = mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerId)
            .param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].leadId").value(matching))
        .andExpect(jsonPath("$[0].category").value("plomeria"))
        .andExpect(jsonPath("$[0].zone").value("Solymar"))
        .andExpect(jsonPath("$[0].urgency").value("alta"))
        .andExpect(jsonPath("$[0].summary").exists())
        .andExpect(jsonPath("$[0].photoCount").value(0))
        .andExpect(jsonPath("$[0].createdAt").exists())
        .andReturn();

    String body = listResult.getResponse().getContentAsString();
    // Privacidad: el DTO de oportunidad NO expone phone ni nombre del cliente.
    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("099222000");
    org.assertj.core.api.Assertions.assertThat(body).doesNotContain("Cliente Test");
    java.util.Map<String, Object> item = JsonPath.read(body, "$[0]");
    org.assertj.core.api.Assertions.assertThat(item).doesNotContainKey("customerPhone");
    org.assertj.core.api.Assertions.assertThat(item).doesNotContainKey("customerName");
    org.assertj.core.api.Assertions.assertThat(item).doesNotContainKey("phone");
  }

  @Test
  void shouldHideAssignedLeadsFromOpportunities() throws Exception {
    Integer providerId = createProvider("Plomeros Asignados", "099111001", "Solymar", "Solymar", "plomeria");
    String token = accessTokenFor(providerId);

    Integer leadId = createReadyLead("099333000", "Perdida de agua importante", "plomeria", "Solymar", "media");

    mockMvc.perform(patch("/api/leads/{id}", leadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"ASSIGNED\", \"assignedProviderId\": %d}".formatted(providerId)))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerId)
            .param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void shouldOrderOpportunitiesByUrgencyThenRecency() throws Exception {
    Integer providerId = createProvider("Plomeros Orden", "099111002", "Solymar", "Solymar", "plomeria");
    String token = accessTokenFor(providerId);

    Integer baja = createReadyLead("099444000", "Gotera lenta en el techo", "plomeria", "Solymar", "baja");
    Integer media = createReadyLead("099444001", "Canilla que gotea", "plomeria", "Solymar", "media");
    Integer alta = createReadyLead("099444002", "Caño roto, inundacion", "plomeria", "Solymar", "alta");

    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerId)
            .param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].leadId").value(alta))
        .andExpect(jsonPath("$[1].leadId").value(media))
        .andExpect(jsonPath("$[2].leadId").value(baja));
  }

  @Test
  void shouldAcceptOpportunityAtomicallyFirstWinsSecondGets409() throws Exception {
    Integer providerA = createProvider("Plomeros A", "099555000", "Solymar", "Solymar", "plomeria");
    Integer providerB = createProvider("Plomeros B", "099555001", "Solymar", "Solymar", "plomeria");
    String tokenA = accessTokenFor(providerA);
    String tokenB = accessTokenFor(providerB);

    Integer leadId = createReadyLead("099666000", "Perdida de agua urgente en cocina", "plomeria", "Solymar", "alta");

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/accept", providerA, leadId)
            .param("token", tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(leadId))
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.customerPhone").value("099666000"))
        .andExpect(jsonPath("$.accessToken").exists());

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/accept", providerB, leadId)
            .param("token", tokenB))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.containsString("tomó este trabajo primero")));

    // El lead queda con el primero (providerA), no con providerB.
    mockMvc.perform(get("/api/public/providers/{id}/me", providerA)
            .param("token", tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedLeads.length()").value(1))
        .andExpect(jsonPath("$.assignedLeads[0].id").value(leadId));

    mockMvc.perform(get("/api/public/providers/{id}/me", providerB)
            .param("token", tokenB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedLeads.length()").value(0));

    // La oportunidad ya no aparece en la bandeja de ninguno de los dos.
    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerA)
            .param("token", tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // Evento de timeline PROVIDER_ACCEPTED presente.
    mockMvc.perform(get("/api/public/leads/{id}/timeline", leadId).param("token", leadAccessTokenFor(leadId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type=='PROVIDER_ACCEPTED')]").isNotEmpty());
  }

  @Test
  void shouldDeclineIdempotentlyAndHideFromThatProviderOnly() throws Exception {
    Integer providerA = createProvider("Plomeros Declina", "099777000", "Solymar", "Solymar", "plomeria");
    Integer providerB = createProvider("Plomeros Ve Igual", "099777001", "Solymar", "Solymar", "plomeria");
    String tokenA = accessTokenFor(providerA);
    String tokenB = accessTokenFor(providerB);

    Integer leadId = createReadyLead("099888000", "Perdida de agua en el patio", "plomeria", "Solymar", "media");

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/decline", providerA, leadId)
            .param("token", tokenA))
        .andExpect(status().isNoContent());

    // Idempotente: repetir el decline no falla.
    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/decline", providerA, leadId)
            .param("token", tokenA))
        .andExpect(status().isNoContent());

    // Desaparece de la bandeja de A...
    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerA)
            .param("token", tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // ...pero sigue visible para B, que no lo rechazó.
    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerB)
            .param("token", tokenB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].leadId").value(leadId));

    mockMvc.perform(get("/api/public/leads/{id}/timeline", leadId).param("token", leadAccessTokenFor(leadId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type=='PROVIDER_DECLINED')]").isNotEmpty());
  }

  @Test
  void shouldRejectInvalidTokenOnAllThreeEndpoints() throws Exception {
    Integer providerId = createProvider("Plomeros Token", "099999000", "Solymar", "Solymar", "plomeria");
    Integer leadId = createReadyLead("099999111", "Perdida de agua para test de token", "plomeria", "Solymar", "media");

    mockMvc.perform(get("/api/public/providers/{id}/opportunities", providerId)
            .param("token", "wrong-token"))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/accept", providerId, leadId)
            .param("token", "wrong-token"))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/decline", providerId, leadId)
            .param("token", "wrong-token"))
        .andExpect(status().isForbidden());
  }
}
