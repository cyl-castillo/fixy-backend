package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pedido estructurado con precio cerrado (Refundación fase 1, contrato §3):
 * {@code POST /api/public/orders}. Usa "Aires Costa" (proveedor seed, cubre
 * toda Ciudad de la Costa) para el camino con técnico, y barométrica en una
 * zona que el único proveedor barométrico seed NO cubre para el camino sin
 * técnico — ver ProviderSeedConfig.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicOrderControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
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
  void pedidoConTecnicoDisponible_creaLeadYContacta() throws Exception {
    persistService("aires_acondicionados", "svc-order-aire", "Service de split", 2900);

    String body = """
        {"serviceCode": "svc-order-aire", "zone": "Lagomar", "timeWindow": "manana_am",
         "name": "Ana", "phone": "099123456", "notes": "es un split de 12.000, piso alto",
         "channel": "web-order"}
        """;

    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.serviceCode").value("svc-order-aire"))
        .andExpect(jsonPath("$.serviceName").value("Service de split"))
        .andExpect(jsonPath("$.priceFrom").value(2900))
        .andExpect(jsonPath("$.matchStatus").value("CONTACTING"))
        .andExpect(jsonPath("$.leadId").exists())
        .andExpect(jsonPath("$.accessToken").exists())
        .andReturn();

    Long leadId = ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();
    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.getDetectedCategory()).isEqualTo("aires_acondicionados");
    assertThat(lead.getLocation()).isEqualTo("Lagomar");
    assertThat(lead.getServiceCode()).isEqualTo("svc-order-aire");
    assertThat(lead.getTimeWindow()).isEqualTo("manana_am");
    assertThat(lead.getUrgency()).isEqualTo("media");
    assertThat(lead.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(lead.isReadyForMatching()).isTrue();

    java.util.List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    assertThat(messages).extracting(LeadMessage::getSender).contains("fixy");
    assertThat(messages.get(0).getText()).contains("Tomé tu pedido").contains("Service de split").contains("Lagomar").contains("$2.900");
  }

  @Test
  void pedidoSinTecnicoDisponible_devuelveNoProvidersYMensajeHonesto() throws Exception {
    persistService("barometrica", "svc-order-bar", "Vaciado de pozo", 3200);

    String body = """
        {"serviceCode": "svc-order-bar", "zone": "Barra de Carrasco", "timeWindow": "hoy",
         "name": "Beto", "phone": "099222333"}
        """;

    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.matchStatus").value("NO_PROVIDERS"))
        .andReturn();

    Long leadId = ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();
    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(lead.getUrgency()).isEqualTo("alta"); // timeWindow=hoy

    java.util.List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    assertThat(messages).extracting(LeadMessage::getText)
        .anyMatch(t -> t.contains("no tengo proveedores libres"));
  }

  @Test
  void remoteConOnSiteContact_agregaElMensajeDeCoordinacion() throws Exception {
    persistService("plomeria", "svc-order-remote", "Visita de plomería", 700);

    String body = """
        {"serviceCode": "svc-order-remote", "zone": "Solymar", "timeWindow": "esta_semana",
         "name": "Carla", "phone": "099333444", "remote": true,
         "onSiteContact": {"name": "Mi madre", "phone": "099000111"}}
        """;

    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();

    Long leadId = ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();
    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.isRemote()).isTrue();
    assertThat(lead.getOnSiteContactName()).isEqualTo("Mi madre");
    assertThat(lead.getOnSiteContactPhone()).isEqualTo("099000111");

    java.util.List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    assertThat(messages.get(0).getText()).contains("Vas a estar fuera").contains("Mi madre");
  }

  @Test
  void serviceCodeInexistente_devuelve400() throws Exception {
    mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"serviceCode": "no-existe", "zone": "Solymar", "timeWindow": "hoy",
                 "name": "Ana", "phone": "099123456"}
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void zonaFueraDeCobertura_devuelve400() throws Exception {
    persistService("plomeria", "svc-order-zone", "Visita de plomería", 700);

    mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"serviceCode": "svc-order-zone", "zone": "Nowhereland", "timeWindow": "hoy",
                 "name": "Ana", "phone": "099123456"}
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void marcadorSmokeEnNotes_preservaLaMarcaEnElProblem() throws Exception {
    persistService("plomeria", "svc-order-smoke", "Visita de plomería", 700);

    String body = """
        {"serviceCode": "svc-order-smoke", "zone": "Solymar", "timeWindow": "hoy",
         "name": "Ana", "phone": "099123456", "notes": "[smoke] prueba automatizada"}
        """;

    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();

    Long leadId = ((Number) com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();
    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.getProblem()).contains("[smoke]");
  }
}
