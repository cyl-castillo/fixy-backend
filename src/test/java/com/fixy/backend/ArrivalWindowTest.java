package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tier 2 (contrato §B.2): franja del técnico — al aceptar (con o sin
 * franja), al confirmar un horario propuesto, y al avisar "voy en camino"
 * con ETA (pisa cualquier franja anterior).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "fixy.payments.provider-commission-enabled=false")
class ArrivalWindowTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private ServiceCatalogItemRepository serviceCatalogItemRepository;

  private ServiceCatalogItem persistService(String code) {
    ServiceCatalogItem item = new ServiceCatalogItem();
    item.setCategory("aires_acondicionados");
    item.setCode(code);
    item.setName("Service de split");
    item.setPriceFrom(2900);
    item.setActive(true);
    return serviceCatalogItemRepository.save(item);
  }

  private record OrderResult(Long leadId, String leadToken, Long providerId, String providerToken) {
  }

  /** Pedido estructurado que auto-matchea con "Aires Costa" (seed) → PROVIDER_CONTACTED. */
  private OrderResult createContactedOrder(String code, String phone) throws Exception {
    persistService(code);
    String body = """
        {"serviceCode": "%s", "zone": "Lagomar", "timeWindow": "manana_am",
         "name": "Cliente Franja", "phone": "%s", "channel": "web-order"}
        """.formatted(code, phone);
    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();
    String leadToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    Lead lead = leadRepository.findById(leadId).orElseThrow();
    Long providerId = lead.getAssignedProviderId();

    MvcResult tokenResult = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String providerToken = JsonPath.read(tokenResult.getResponse().getContentAsString(), "$.accessToken");
    return new OrderResult(leadId, leadToken, providerId, providerToken);
  }

  private List<LeadMessage> messagesFor(Long leadId) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
  }

  @Test
  void aceptarConFranjaDesdeStatusMuestraLaFranjaEnElMensaje() throws Exception {
    OrderResult ctx = createContactedOrder("svc-arrival-status-con", "099400001");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\",\"arrivalWindow\":\"hoy de 14 a 18\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.arrivalWindow").value("hoy de 14 a 18"));

    Lead lead = leadRepository.findById(ctx.leadId()).orElseThrow();
    assertThat(lead.getArrivalWindow()).isEqualTo("hoy de 14 a 18");
    assertThat(messagesFor(ctx.leadId())).extracting(LeadMessage::getText)
        .anyMatch(t -> t.contains("tomó tu pedido y pasa **hoy de 14 a 18**"));
  }

  @Test
  void aceptarSinFranjaDesdeStatusUsaElMensajeGenerico() throws Exception {
    OrderResult ctx = createContactedOrder("svc-arrival-status-sin", "099400002");

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\"}"))
        .andExpect(status().isOk());

    Lead lead = leadRepository.findById(ctx.leadId()).orElseThrow();
    assertThat(lead.getArrivalWindow()).isNull();
    assertThat(messagesFor(ctx.leadId())).extracting(LeadMessage::getText)
        .anyMatch(t -> t.contains("tomó tu pedido. Te confirma día y hora por acá."));
  }

  @Test
  void aceptarConFranjaDesdeLaBandejaDelPozoTambienLaGuarda() throws Exception {
    // Lead nunca contactado (pool abierto, status NEW) — mismo endpoint que
    // usa la bandeja de oportunidades.
    String payload = """
        {"name": "Cliente Pozo", "phone": "099400003", "problem": "aire no enfria",
         "channel": "web-app", "serviceCategory": "aires_acondicionados", "zone": "Lagomar", "urgency": "media"}
        """;
    MvcResult leadResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.readyForMatching").value(true))
        .andReturn();
    Long leadId = ((Number) JsonPath.read(leadResult.getResponse().getContentAsString(), "$.id")).longValue();

    // "Aires Costa" es el único proveedor seed de la categoría; tomarlo del pozo.
    MvcResult opportunitiesProviderId = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/providers")
                .with(httpBasic("test-ops", "test-pass")))
        .andReturn();
    // Usamos el mismo proveedor seed que el resto de la suite ("Aires Costa").
    List<?> providers = JsonPath.read(opportunitiesProviderId.getResponse().getContentAsString(), "$[?(@.name=='Aires Costa')]");
    assertThat(providers).isNotEmpty();
    Integer providerId = (Integer) ((java.util.Map<?, ?>) providers.get(0)).get("id");

    MvcResult tokenResult = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String providerToken = JsonPath.read(tokenResult.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/accept", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"arrivalWindow\":\"mañana temprano\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.arrivalWindow").value("mañana temprano"));

    assertThat(leadRepository.findById(leadId).orElseThrow().getArrivalWindow()).isEqualTo("mañana temprano");
  }

  @Test
  void confirmarUnHorarioPropuestoLoConvierteEnFranja() throws Exception {
    OrderResult ctx = createContactedOrder("svc-arrival-schedule", "099400004");
    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/schedule-proposal", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"proposal\":\"mañana de 14 a 16\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/leads/{id}/schedule-response", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accept\": true}"))
        .andExpect(status().isOk());

    assertThat(leadRepository.findById(ctx.leadId()).orElseThrow().getArrivalWindow())
        .isEqualTo("mañana de 14 a 16");
  }

  @Test
  void avisarQueVaEnCaminoConEtaPisaLaFranjaAnterior() throws Exception {
    OrderResult ctx = createContactedOrder("svc-arrival-onmyway", "099400005");
    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\",\"arrivalWindow\":\"hoy de 14 a 18\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/on-my-way", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"etaMinutes\": 25}"))
        .andExpect(status().isOk());

    assertThat(leadRepository.findById(ctx.leadId()).orElseThrow().getArrivalWindow())
        .isEqualTo("hoy, llega en ~25 min");
  }
}
