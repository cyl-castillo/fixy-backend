package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tier 1 (contrato TIER1_CONTRATO.md §C): "el cargo se decide al reservar" —
 * {@code serviceFeeOptOut} en el pedido estructurado ({@code POST
 * /api/public/orders}) marca el lead, cambia el mensaje de confirmación, y
 * al completar el trabajo NUNCA crea {@link com.fixy.backend.model.CustomerPayment}
 * ni pide activar garantía — mismo patrón de contexto que
 * {@link CustomerServiceFeeFlowTest} (service-fee-enabled=true, comisión al
 * técnico apagada).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.orders.service-fee-enabled=true",
    "fixy.orders.service-fee-percent=15",
    "fixy.payments.provider-commission-enabled=false"
})
class ServiceFeeOptOutTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private CustomerPaymentRepository customerPaymentRepository;
  @Autowired private LeadRatingRepository leadRatingRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ServiceCatalogItemRepository serviceCatalogItemRepository;

  private ServiceCatalogItem persistService(String category, String code, String name, int priceFrom) {
    ServiceCatalogItem item = new ServiceCatalogItem();
    item.setCategory(category);
    item.setCode(code);
    item.setName(name);
    item.setPriceFrom(priceFrom);
    item.setActive(true);
    return serviceCatalogItemRepository.save(item);
  }

  @Test
  void pedidoConOptOut_marcaElLeadYCambiaElMensajeDeConfirmacion() throws Exception {
    persistService("aires_acondicionados", "svc-optout-aire", "Service de split", 2900);

    String body = """
        {"serviceCode": "svc-optout-aire", "zone": "Lagomar", "timeWindow": "manana_am",
         "name": "Nico", "phone": "099630001", "channel": "web-order", "serviceFeeOptOut": true}
        """;

    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();

    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.isServiceFeeOptOut()).isTrue();

    List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    assertThat(messages.get(0).getText())
        .contains("sin garantía Fixy (elegiste pagar solo al técnico)");
  }

  @Test
  void pedidoSinOptOut_mantieneElMensajeConGarantia() throws Exception {
    persistService("aires_acondicionados", "svc-nooptout-aire", "Service de split", 2900);

    String body = """
        {"serviceCode": "svc-nooptout-aire", "zone": "Lagomar", "timeWindow": "manana_am",
         "name": "Nico", "phone": "099630002", "channel": "web-order"}
        """;

    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();

    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.isServiceFeeOptOut()).isFalse();

    List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    assertThat(messages.get(0).getText()).contains("incluye servicio Fixy y garantía");
  }

  @Test
  void completarUnPedidoConOptOut_noCreaCargoDeServicioYMandaElMensajeDelContrato() throws Exception {
    persistService("aires_acondicionados", "svc-optout-complete", "Service de split", 2900);

    String body = """
        {"serviceCode": "svc-optout-complete", "zone": "Lagomar", "timeWindow": "manana_am",
         "name": "Nico", "phone": "099630003", "channel": "web-order", "serviceFeeOptOut": true}
        """;

    MvcResult orderRes = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(orderRes.getResponse().getContentAsString(), "$.leadId")).longValue();
    String leadToken = JsonPath.read(orderRes.getResponse().getContentAsString(), "$.accessToken");

    Long providerId = leadRepository.findById(leadId).orElseThrow().getAssignedProviderId();
    assertThat(providerId).isNotNull();
    Provider provider = providerRepository.findById(providerId).orElseThrow();
    if (provider.getAccessToken() == null || provider.getAccessToken().isBlank()) {
      provider.setAccessToken(UUID.randomUUID().toString().replace("-", ""));
      providerRepository.save(provider);
    }
    String providerToken = provider.getAccessToken();

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\", \"amountCharged\": 2900.00}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    // Contrato §C.2: nunca se crea CustomerPayment para este lead.
    assertThat(customerPaymentRepository.findByLeadId(leadId)).isEmpty();

    boolean optOutMessagePosted = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .anyMatch(m -> m.getText() != null
            && m.getText().contains("Elegiste sin garantía Fixy: no hay nada más que pagar. ¿Quedó todo bien?"));
    assertThat(optOutMessagePosted).isTrue();

    // La reseña nace SIN verificar (no hay pago posible para este lead).
    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", leadId)
            .param("token", leadToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 5}"))
        .andExpect(status().isOk());
    assertThat(leadRatingRepository.findByLeadId(leadId).orElseThrow().isVerified()).isFalse();
  }
}
