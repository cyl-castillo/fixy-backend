package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tier 1 (contrato TIER1_CONTRATO.md §B.1): protocolo "al llegar" — el
 * proveedor propone un precio nuevo, el cliente acepta o rechaza, y el
 * cierre del trabajo respeta esa decisión. commission-enabled=false para
 * que {@code amountCharged} sea opcional salvo donde el test lo necesita a
 * propósito (mismo patrón que ProviderCancelReasonTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.provider-commission-enabled=false",
    "fixy.orders.service-fee-enabled=false"
})
class PriceChangeFlowTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private LeadEventRepository leadEventRepository;

  private record ProviderAndLead(Integer providerId, String providerToken, Integer leadId, String leadToken) {
  }

  private ProviderAndLead createAssignedLead(String providerPhone, String leadPhone) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Plomeria Price Change Test",
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
                  "problem": "Necesito plomero para prueba de cambio de precio",
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

  private void uploadProviderPhoto(ProviderAndLead ctx) throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "antes.jpg", "image/jpeg", "fake-image".getBytes());
    mockMvc.perform(multipart("/api/public/providers/{id}/leads/{lid}/photos", ctx.providerId(), ctx.leadId())
            .file(file)
            .param("token", ctx.providerToken()))
        .andExpect(status().isCreated());
  }

  private void propose(ProviderAndLead ctx, String amount, String reason) throws Exception {
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/price-change", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\": %s, \"reason\": \"%s\"}".formatted(amount, reason)))
        .andExpect(status().isOk());
  }

  @Test
  void proponerSinFotoDevuelve400ConElMensajeDelContrato() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099620001", "099620101");

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/price-change", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\": 4500, \"reason\": \"encontró más daño\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value(
            "Subí una foto de lo que encontraste antes de proponer el precio nuevo"));
  }

  @Test
  void proponerConFotoGeneraMensajeTimelineYExponeLaPropuesta() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099620002", "099620102");
    uploadProviderPhoto(ctx);

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/price-change", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\": 4500, \"reason\": \"encontró más daño\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priceChange.status").value("PENDING"))
        .andExpect(jsonPath("$.priceChange.proposedAmount").value(4500))
        .andExpect(jsonPath("$.priceChange.reason").value("encontró más daño"));

    boolean messagePosted = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(Long.valueOf(ctx.leadId())).stream()
        .anyMatch(m -> m.getText() != null && m.getText().contains("propone un precio nuevo")
            && m.getText().contains("$4.500") && m.getText().contains("Nada se hace hasta que aceptes"));
    assertThat(messagePosted).isTrue();

    boolean timelineEvent = leadEventRepository.findByLeadIdOrderByCreatedAtAsc(Long.valueOf(ctx.leadId())).stream()
        .anyMatch(e -> "PRICE_CHANGE_PROPOSED".equals(e.getType()));
    assertThat(timelineEvent).isTrue();
  }

  @Test
  void aceptarSinPropuestaPendienteDevuelve409() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099620003", "099620103");

    mockMvc.perform(post("/api/public/leads/{id}/price-change/accept", ctx.leadId())
            .param("token", ctx.leadToken()))
        .andExpect(status().isConflict());
  }

  @Test
  void aceptarGuardaAgreedYMandaMensajeAlCliente() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099620004", "099620104");
    uploadProviderPhoto(ctx);
    propose(ctx, "4500", "encontró más daño");

    mockMvc.perform(post("/api/public/leads/{id}/price-change/accept", ctx.leadId())
            .param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.agreedAmount").value(4500));

    boolean messagePosted = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(Long.valueOf(ctx.leadId())).stream()
        .anyMatch(m -> "Aceptaste $4.500. El técnico ya puede seguir.".equals(m.getText()));
    assertThat(messagePosted).isTrue();

    boolean timelineEvent = leadEventRepository.findByLeadIdOrderByCreatedAtAsc(Long.valueOf(ctx.leadId())).stream()
        .anyMatch(e -> "PRICE_CHANGE_ACCEPTED".equals(e.getType()) && "customer".equals(e.getActor()));
    assertThat(timelineEvent).isTrue();

    // Ya no queda pendiente: aceptar/rechazar de nuevo es 409.
    mockMvc.perform(post("/api/public/leads/{id}/price-change/reject", ctx.leadId())
            .param("token", ctx.leadToken()))
        .andExpect(status().isConflict());
  }

  @Test
  void rechazarAvisaAlClienteYNoBloqueaCompletar() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099620005", "099620105");
    uploadProviderPhoto(ctx);
    propose(ctx, "4500", "encontró más daño");

    mockMvc.perform(post("/api/public/leads/{id}/price-change/reject", ctx.leadId())
            .param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    boolean messagePosted = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(Long.valueOf(ctx.leadId())).stream()
        .anyMatch(m -> "Avisamos al técnico y a Fixy. Una persona te escribe.".equals(m.getText()));
    assertThat(messagePosted).isTrue();

    boolean timelineEvent = leadEventRepository.findByLeadIdOrderByCreatedAtAsc(Long.valueOf(ctx.leadId())).stream()
        .anyMatch(e -> "PRICE_CHANGE_REJECTED".equals(e.getType()));
    assertThat(timelineEvent).isTrue();

    // Contrato §B.1: el guard de completar solo bloquea propuesta PENDIENTE
    // (proposed_at no nulo, agreed_at nulo, NO rechazada) — una rechazada no
    // bloquea el cierre.
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
  }

  @Test
  void completarConPropuestaPendienteDevuelve409ConMensajeDelContrato() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099620006", "099620106");
    uploadProviderPhoto(ctx);
    propose(ctx, "4500", "encontró más daño");

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.message").value(
            "El vecino todavía no aceptó el precio nuevo. Esperá su OK o cancelá la propuesta."));
  }

  @Test
  void completarConMontoMayorAlAceptadoDevuelve400() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099620007", "099620107");
    uploadProviderPhoto(ctx);
    propose(ctx, "4500", "encontró más daño");
    mockMvc.perform(post("/api/public/leads/{id}/price-change/accept", ctx.leadId())
            .param("token", ctx.leadToken()))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 5000.00}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value("Cobraste más de lo que el vecino aceptó ($4.500)."));
  }

  @Test
  void completarConMontoDentroDeLoAceptadoOk() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099620008", "099620108");
    uploadProviderPhoto(ctx);
    propose(ctx, "4500", "encontró más daño");
    mockMvc.perform(post("/api/public/leads/{id}/price-change/accept", ctx.leadId())
            .param("token", ctx.leadToken()))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 4500.00}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
  }

  @Test
  void unaPropuestaNuevaReemplazaLaAnteriorSinResponder() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099620009", "099620109");
    uploadProviderPhoto(ctx);
    propose(ctx, "4500", "encontró más daño");
    propose(ctx, "5200", "todavía más daño");

    mockMvc.perform(post("/api/public/leads/{id}/price-change/accept", ctx.leadId())
            .param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agreedAmount").value(5200));
  }
}
