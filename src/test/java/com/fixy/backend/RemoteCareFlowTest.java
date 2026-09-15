package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Provider;
import com.fixy.backend.model.RemoteCarePlan;
import com.fixy.backend.model.RemoteCarePlanStatus;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.repository.RemoteCarePlanRepository;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import com.fixy.backend.service.MercadoPagoService;
import com.jayway.jsonpath.JsonPath;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Refundación de Fixy, fase 2 (contrato §B): plan Casa a distancia de punta
 * a punta — pedido público → activación de ops → pedido remoto sobre el
 * plan → evidencia de fotos obligatoria para completar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.orders.service-fee-enabled=false",
    "fixy.payments.provider-commission-enabled=false"
})
class RemoteCareFlowTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private RemoteCarePlanRepository remoteCarePlanRepository;
  @Autowired private LeadRepository leadRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ServiceCatalogItemRepository serviceCatalogItemRepository;

  @MockitoBean private MercadoPagoService mercadoPagoService;

  /** Tests corren sin Flyway (H2 + ddl-auto=update): V29 no siembra
   * service_catalog acá, mismo patrón que PublicOrderControllerTest. */
  private ServiceCatalogItem persistService(String category, String code, String name, int priceFrom) {
    if (serviceCatalogItemRepository.findByCode(code).isPresent()) {
      return serviceCatalogItemRepository.findByCode(code).get();
    }
    ServiceCatalogItem item = new ServiceCatalogItem();
    item.setCategory(category);
    item.setCode(code);
    item.setName(name);
    item.setPriceFrom(priceFrom);
    item.setActive(true);
    return serviceCatalogItemRepository.save(item);
  }

  private RemoteCarePlan requestPlan(String ownerPhone) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/public/remote-care/requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "ownerName": "Marta Dueña",
                  "phone": "%s",
                  "zone": "Solymar",
                  "address": "Calle Falsa 123",
                  "onSiteName": "Rosa (cuidadora)",
                  "onSitePhone": "099700200"
                }
                """.formatted(ownerPhone)))
        .andExpect(status().isCreated())
        .andReturn();
    Long planId = ((Number) JsonPath.read(res.getResponse().getContentAsString(), "$.planId")).longValue();
    return remoteCarePlanRepository.findById(planId).orElseThrow();
  }

  @Test
  void pedirUnPlanLoDejaEnRequestedYApareceEnOps() throws Exception {
    RemoteCarePlan plan = requestPlan("099700001");
    assertThat(plan.getStatus()).isEqualTo(RemoteCarePlanStatus.REQUESTED);

    mockMvc.perform(get("/api/ops/remote-care").with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == %d)].status".formatted(plan.getId()))
            .value(org.hamcrest.Matchers.hasItem("REQUESTED")));
  }

  @Test
  void honeypotNoPersisteNada() throws Exception {
    long before = remoteCarePlanRepository.count();
    mockMvc.perform(post("/api/public/remote-care/requests")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "ownerName": "Bot", "phone": "099700099", "zone": "Solymar",
                  "address": "Calle Falsa 1", "website": "http://bot.example"
                }
                """))
        .andExpect(status().isCreated());
    assertThat(remoteCarePlanRepository.count()).isEqualTo(before);
  }

  @Test
  void ordenSoloConPlanActivoYCreaPedidoRemoto() throws Exception {
    persistService("plomeria", "plo_destape", "Destape de pileta, inodoro o cañería", 1900);
    when(mercadoPagoService.createPreference(anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PreferenceResult("pref-plan", "https://mp.test/pref-plan")));

    RemoteCarePlan plan = requestPlan("099700002");

    // Todavía REQUESTED: 403 con mensaje humano.
    mockMvc.perform(post("/api/public/remote-care/plans/{id}/orders", plan.getId())
            .param("token", plan.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"serviceCode": "plo_destape", "zone": "Solymar", "timeWindow": "coordinar"}
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("activando")));

    mockMvc.perform(post("/api/ops/remote-care/{id}/activate", plan.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    MvcResult orderRes = mockMvc.perform(post("/api/public/remote-care/plans/{id}/orders", plan.getId())
            .param("token", plan.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"serviceCode": "plo_destape", "zone": "Solymar", "timeWindow": "coordinar"}
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(orderRes.getResponse().getContentAsString(), "$.leadId")).longValue();

    mockMvc.perform(get("/api/public/remote-care/plans/{id}", plan.getId()).param("token", plan.getAccessToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders[?(@.leadId == %d)]".formatted(leadId)).exists());
  }

  @Test
  void trabajoRemotoExigeFotoDelProveedorAntesDeCompletar() throws Exception {
    persistService("plomeria", "plo_destape", "Destape de pileta, inodoro o cañería", 1900);
    when(mercadoPagoService.createPreference(anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PreferenceResult("pref-photo", "https://mp.test/pref-photo")));

    RemoteCarePlan plan = requestPlan("099700003");
    mockMvc.perform(post("/api/ops/remote-care/{id}/activate", plan.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    MvcResult orderRes = mockMvc.perform(post("/api/public/remote-care/plans/{id}/orders", plan.getId())
            .param("token", plan.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"serviceCode": "plo_destape", "zone": "Solymar", "timeWindow": "coordinar"}
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(orderRes.getResponse().getContentAsString(), "$.leadId")).longValue();

    // El matching automático (proveedor seed "Plomería Solymar" u otro que
    // cubra la zona) ya debería haber contactado a alguien — se busca cuál
    // en vez de asumirlo, así el test no depende del seed de proveedores.
    Long providerId = leadRepository.findById(leadId).orElseThrow().getAssignedProviderId();
    assertThat(providerId).isNotNull();
    Provider provider = providerRepository.findById(providerId).orElseThrow();
    if (provider.getAccessToken() == null || provider.getAccessToken().isBlank()) {
      provider.setAccessToken(java.util.UUID.randomUUID().toString().replace("-", ""));
      providerRepository.save(provider);
    }
    String providerToken = provider.getAccessToken();

    // Aceptar el trabajo primero (ASSIGNED) para poder completarlo.
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\"}"))
        .andExpect(status().isOk());

    // Sin foto: 400 con el mensaje del contrato.
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"COMPLETED\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value(
            org.hamcrest.Matchers.containsString("Subí al menos una foto")));

    // Sube una foto como proveedor.
    MockMultipartFile file = new MockMultipartFile("file", "antes.jpg", "image/jpeg", "fake-image".getBytes());
    mockMvc.perform(multipart("/api/public/providers/{id}/leads/{lid}/photos", providerId, leadId)
            .file(file)
            .param("token", providerToken))
        .andExpect(status().isCreated());

    // Ahora sí completa.
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"COMPLETED\", \"amountCharged\": 1900.00}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
  }

  @Test
  void opsPuedePausarCancelarYCrearVisitaPreventiva() throws Exception {
    persistService("plomeria", "plo_destape", "Destape de pileta, inodoro o cañería", 1900);
    persistService("plomeria", "care_visita", "Visita preventiva del plan Casa a distancia", 0);
    RemoteCarePlan plan = requestPlan("099700004");
    mockMvc.perform(post("/api/ops/remote-care/{id}/activate", plan.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/ops/remote-care/{id}/visit", plan.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.priceFrom").value(0));

    mockMvc.perform(patch("/api/ops/remote-care/{id}/note", plan.getId())
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nextVisitNote\": \"Visita de primavera, coordinar con Rosa\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextVisitNote").value("Visita de primavera, coordinar con Rosa"));

    mockMvc.perform(get("/api/ops/remote-care/{id}/link", plan.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.containsString("/mi-casa/" + plan.getId() + "/")));

    mockMvc.perform(post("/api/ops/remote-care/{id}/pause", plan.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAUSED"));

    mockMvc.perform(post("/api/public/remote-care/plans/{id}/orders", plan.getId())
            .param("token", plan.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"serviceCode": "plo_destape", "zone": "Solymar", "timeWindow": "coordinar"}
                """))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/api/ops/remote-care/{id}/cancel", plan.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }
}
