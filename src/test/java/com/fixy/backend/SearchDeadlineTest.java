package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tier 2 (contrato §B.3): hora límite de búsqueda de técnico — arranca al
 * quedar {@code readyForMatching}, se reinicia al cambiar la franja o al
 * volver al pozo, y alimenta {@code LeadResponse.matchingState}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SearchDeadlineTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
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
  void pedidoEstructuradoArrancaLaHoraLimiteYQuedaEnEstadoContacted() throws Exception {
    persistService("aires_acondicionados", "svc-deadline-aire", "Service de split", 2900);

    String body = """
        {"serviceCode": "svc-deadline-aire", "zone": "Lagomar", "timeWindow": "manana_am",
         "name": "Ana", "phone": "099123001", "channel": "web-order"}
        """;

    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();
    String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");

    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(lead.getSearchDeadlineAt()).isNotNull();
    // ~120 min por default (con margen por el tiempo de ejecución del test).
    long minutesAhead = java.time.Duration.between(OffsetDateTime.now(), lead.getSearchDeadlineAt()).toMinutes();
    assertThat(minutesAhead).isBetween(115L, 121L);

    mockMvc.perform(get("/api/public/leads/{id}", leadId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matchingState").value("CONTACTED"))
        .andExpect(jsonPath("$.searchDeadlineAt").exists());
  }

  @Test
  void cambiarLaFranjaReiniciaLaHoraLimiteYAvisaAlCliente() throws Exception {
    persistService("aires_acondicionados", "svc-deadline-window", "Service de split", 2900);
    String body = """
        {"serviceCode": "svc-deadline-window", "zone": "Lagomar", "timeWindow": "manana_am",
         "name": "Cami", "phone": "099123002", "channel": "web-order"}
        """;
    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();
    String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    OffsetDateTime originalDeadline = leadRepository.findById(leadId).orElseThrow().getSearchDeadlineAt();

    mockMvc.perform(post("/api/public/leads/{id}/time-window", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"timeWindow\": \"esta_semana\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeWindow").value("esta_semana"));

    Lead reloaded = leadRepository.findById(leadId).orElseThrow();
    assertThat(reloaded.getTimeWindow()).isEqualTo("esta_semana");
    assertThat(reloaded.getSearchDeadlineAt()).isNotEqualTo(originalDeadline);
  }

  @Test
  void noSePuedeCambiarLaFranjaConTecnicoYaAsignado() throws Exception {
    persistService("aires_acondicionados", "svc-deadline-assigned", "Service de split", 2900);
    String body = """
        {"serviceCode": "svc-deadline-assigned", "zone": "Lagomar", "timeWindow": "manana_am",
         "name": "Nico", "phone": "099123003", "channel": "web-order"}
        """;
    MvcResult result = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.leadId")).longValue();
    String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    Lead lead = leadRepository.findById(leadId).orElseThrow();

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", lead.getAssignedProviderId(), leadId)
            .param("token", providerToken(lead.getAssignedProviderId()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\"}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/leads/{id}/time-window", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"timeWindow\": \"esta_semana\"}"))
        .andExpect(status().isConflict());

    Lead reloaded = leadRepository.findById(leadId).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.ASSIGNED);
    // ASSIGNED+: searchDeadlineAt ya no se expone en la respuesta pública.
    mockMvc.perform(get("/api/public/leads/{id}", leadId).param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.searchDeadlineAt").doesNotExist())
        .andExpect(jsonPath("$.matchingState").doesNotExist());
  }

  private String providerToken(Long providerId) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }
}
