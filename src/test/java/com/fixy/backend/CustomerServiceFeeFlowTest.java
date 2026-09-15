package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.service.MercadoPagoService;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Refundación de Fixy, fase 2 (contrato §A): cargo de servicio al cliente
 * de punta a punta — completar con monto → CustomerPayment PENDING con
 * link → webhook "customer:{id}" PAID → garantía activa + reseña
 * verificada. Contexto propio (service-fee-enabled=true,
 * provider-commission-enabled=false) — el default de esta fase.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.orders.service-fee-enabled=true",
    "fixy.orders.service-fee-percent=15",
    "fixy.payments.provider-commission-enabled=false",
    "fixy.guarantee.days=30"
})
class CustomerServiceFeeFlowTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private CustomerPaymentRepository customerPaymentRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private LeadRatingRepository leadRatingRepository;

  @MockitoBean private MercadoPagoService mercadoPagoService;

  private record ProviderAndLead(Integer providerId, String providerToken, Integer leadId, String leadToken) {
  }

  private ProviderAndLead createAssignedLead(String providerPhone, String leadPhone) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Plomeria Service Fee Test",
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
                  "problem": "Necesito plomero para prueba de cargo de servicio",
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

  @Test
  void completarSinMontoExigeElMontoConElMensajeDelContrato() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099610001", "099610101");

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value(
            org.hamcrest.Matchers.containsString("cuánto cobraste")));
  }

  @Test
  void completarConMontoCreaElCargoDeServicioConLinkYAvisaAlCliente() throws Exception {
    when(mercadoPagoService.createPreference(anyString(), anyString(), org.mockito.ArgumentMatchers.any(),
        anyString(), anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PreferenceResult("pref-abc", "https://mp.test/pref-abc")));

    ProviderAndLead ctx = createAssignedLead("099610002", "099610102");

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 3500.00}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    CustomerPayment payment = customerPaymentRepository.findByLeadId(Long.valueOf(ctx.leadId())).orElseThrow();
    assertThat(payment.getKind()).isEqualTo(CustomerPaymentKind.SERVICE_FEE);
    assertThat(payment.getStatus()).isEqualTo(CustomerPaymentStatus.PENDING);
    // 15% de 3500 = 525.
    assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("525"));
    assertThat(payment.getMpPaymentLink()).isEqualTo("https://mp.test/pref-abc");

    boolean serviceFeeMessagePosted = leadMessageRepository
        .findByLeadIdOrderByCreatedAtAsc(Long.valueOf(ctx.leadId())).stream()
        .anyMatch(m -> m.getText() != null && m.getText().contains("garantía Fixy de 30 días")
            && m.getText().contains("https://mp.test/pref-abc"));
    assertThat(serviceFeeMessagePosted).isTrue();

    // No se creó comisión al técnico (provider-commission-enabled=false).
    mockMvc.perform(get("/api/ops/payments")
            .with(httpBasic("test-ops", "test-pass"))
            .param("status", "PENDING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.leadId == %d)]".formatted(ctx.leadId())).isEmpty());

    // Ficha pública del lead expone el cargo de servicio.
    mockMvc.perform(get("/api/public/leads/{id}", ctx.leadId()).param("token", ctx.leadToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serviceFee.amount").value(525))
        .andExpect(jsonPath("$.serviceFee.status").value("PENDING"))
        .andExpect(jsonPath("$.serviceFee.paymentLink").value("https://mp.test/pref-abc"));
  }

  @Test
  void completarSinMpHabilitadoAvisaPorWhatsappSinRomper() throws Exception {
    when(mercadoPagoService.createPreference(anyString(), anyString(), org.mockito.ArgumentMatchers.any(),
        anyString(), anyString()))
        .thenReturn(Optional.empty());

    ProviderAndLead ctx = createAssignedLead("099610003", "099610103");

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 1000.00}"))
        .andExpect(status().isOk());

    CustomerPayment payment = customerPaymentRepository.findByLeadId(Long.valueOf(ctx.leadId())).orElseThrow();
    assertThat(payment.getStatus()).isEqualTo(CustomerPaymentStatus.PENDING);
    assertThat(payment.getMpPaymentLink()).isNull();

    boolean whatsappFallbackMessage = leadMessageRepository
        .findByLeadIdOrderByCreatedAtAsc(Long.valueOf(ctx.leadId())).stream()
        .anyMatch(m -> m.getText() != null && m.getText().contains("link de pago por WhatsApp"));
    assertThat(whatsappFallbackMessage).isTrue();
  }

  @Test
  void webhookCustomerPrefixMarcaPagoActivaGarantiaYVerificaResenaPosterior() throws Exception {
    when(mercadoPagoService.createPreference(anyString(), anyString(), org.mockito.ArgumentMatchers.any(),
        anyString(), anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PreferenceResult("pref-xyz", "https://mp.test/pref-xyz")));

    ProviderAndLead ctx = createAssignedLead("099610004", "099610104");
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 2000.00}"))
        .andExpect(status().isOk());

    CustomerPayment payment = customerPaymentRepository.findByLeadId(Long.valueOf(ctx.leadId())).orElseThrow();
    String mpPaymentId = "mp-service-fee-1";
    when(mercadoPagoService.fetchPayment(mpPaymentId))
        .thenReturn(Optional.of(new MercadoPagoService.PaymentStatusResult(
            mpPaymentId, "approved", "customer:" + payment.getId())));

    mockMvc.perform(post("/api/webhooks/mercadopago")
            .param("type", "payment")
            .param("data.id", mpPaymentId))
        .andExpect(status().isOk());

    CustomerPayment paid = customerPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(paid.getStatus()).isEqualTo(CustomerPaymentStatus.PAID);
    assertThat(paid.getGuaranteeUntil()).isNotNull();
    assertThat(paid.getGuaranteeUntil()).isAfter(java.time.OffsetDateTime.now().plusDays(29));

    boolean guaranteeMessage = leadMessageRepository
        .findByLeadIdOrderByCreatedAtAsc(Long.valueOf(ctx.leadId())).stream()
        .anyMatch(m -> m.getText() != null && m.getText().contains("Garantía Fixy activa hasta"));
    assertThat(guaranteeMessage).isTrue();

    // Idempotencia: repetir el webhook no reprocesa.
    mockMvc.perform(post("/api/webhooks/mercadopago")
            .param("type", "payment")
            .param("data.id", mpPaymentId))
        .andExpect(status().isOk());
    CustomerPayment stillPaid = customerPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(stillPaid.getGuaranteeUntil()).isEqualTo(paid.getGuaranteeUntil());

    // La reseña que llega DESPUÉS del pago nace verificada de una.
    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 5}"))
        .andExpect(status().isOk());

    boolean verified = leadRatingRepository.findByLeadId(Long.valueOf(ctx.leadId())).orElseThrow().isVerified();
    assertThat(verified).isTrue();
  }

  @Test
  void resenaQueLlegaAntesDelPagoNaceSinVerificarYSeVerificaAlPagar() throws Exception {
    when(mercadoPagoService.createPreference(anyString(), anyString(), org.mockito.ArgumentMatchers.any(),
        anyString(), anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PreferenceResult("pref-early", "https://mp.test/pref-early")));

    ProviderAndLead ctx = createAssignedLead("099610005", "099610105");
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 1500.00}"))
        .andExpect(status().isOk());

    // El cliente califica ANTES de pagar.
    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 4}"))
        .andExpect(status().isOk());

    assertThat(leadRatingRepository.findByLeadId(Long.valueOf(ctx.leadId())).orElseThrow().isVerified()).isFalse();

    CustomerPayment payment = customerPaymentRepository.findByLeadId(Long.valueOf(ctx.leadId())).orElseThrow();
    String mpPaymentId = "mp-service-fee-early";
    when(mercadoPagoService.fetchPayment(mpPaymentId))
        .thenReturn(Optional.of(new MercadoPagoService.PaymentStatusResult(
            mpPaymentId, "approved", "customer:" + payment.getId())));

    mockMvc.perform(post("/api/webhooks/mercadopago")
            .param("type", "payment")
            .param("data.id", mpPaymentId))
        .andExpect(status().isOk());

    assertThat(leadRatingRepository.findByLeadId(Long.valueOf(ctx.leadId())).orElseThrow().isVerified()).isTrue();
  }

  @Test
  void opsPuedeMarcarPagadoYCondonarManualmente() throws Exception {
    ProviderAndLead ctx = createAssignedLead("099610006", "099610106");
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", ctx.providerId(), ctx.leadId())
            .param("token", ctx.providerToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 1000.00}"))
        .andExpect(status().isOk());

    CustomerPayment payment = customerPaymentRepository.findByLeadId(Long.valueOf(ctx.leadId())).orElseThrow();

    mockMvc.perform(patch("/api/ops/customer-payments/{id}/mark-paid", payment.getId())
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\": \"efectivo\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAID"));

    // Ya no se puede condonar lo cobrado.
    mockMvc.perform(patch("/api/ops/customer-payments/{id}/waive", payment.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isConflict());
  }
}
