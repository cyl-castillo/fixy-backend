package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.ProviderSelfService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Motivo obligatorio al cancelar (mejora 2026-08-19): cancelar desde el
 * panel del proveedor sin decir por qué no avisaba a nadie y no dejaba
 * rastro del motivo. Contrato con el frontend: {@code cancelReason}
 * ("sin_disponibilidad"|"zona"|"precio"|"otro") obligatorio cuando
 * {@code status == CANCELLED}; {@code cancelReasonDetail} libre, opcional,
 * máx 300 caracteres. Se persiste en el evento PROVIDER_RELEASED existente
 * (sin tabla nueva) y dispara {@code TelegramNotifyService.notifyProviderCancelled}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.enabled=false"
})
class ProviderCancelReasonTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private ProviderRepository providerRepository;

  private Lead makeOrphanWaiting(String zone) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer id = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    Lead lead = leadRepository.findById(Long.valueOf(id)).orElseThrow();
    lead.setProblem("Pedido de plomería para prueba de cancelReason");
    lead.setDetectedCategory("plomeria");
    lead.setLocation(zone);
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.NEW);
    return leadRepository.save(lead);
  }

  private Provider createPlomero(String name, String zone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("099000333");
    provider.setCategories("plomeria");
    provider.setPrimaryZone(zone);
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setAccessToken("token-" + name.replace(' ', '-'));
    return providerRepository.save(provider);
  }

  private Lead contactProvider(Lead lead, Provider provider) {
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    return leadRepository.save(lead);
  }

  @Test
  void cancelarSinMotivoDevuelve400YNoTocaElLead() throws Exception {
    String zone = "Zona CancelReason Uno";
    Provider provider = createPlomero("Plomero Sin Motivo", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isBadRequest());

    Lead untouched = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(untouched.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(untouched.getAssignedProviderId()).isEqualTo(provider.getId());
  }

  @Test
  void cancelarConMotivoVacioDevuelve400() throws Exception {
    String zone = "Zona CancelReason Dos";
    Provider provider = createPlomero("Plomero Motivo Vacio", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"cancelReason\":\"   \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cancelarConDetalleDemasiadoLargoDevuelve400() throws Exception {
    String zone = "Zona CancelReason Tres";
    Provider provider = createPlomero("Plomero Detalle Largo", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);
    String detalleLargo = "x".repeat(301);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(("{\"status\":\"CANCELLED\",\"cancelReason\":\"otro\",\"cancelReasonDetail\":\"%s\"}")
                .formatted(detalleLargo)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cancelarConMotivoLoPersisteEnElEventoExistenteYLiberaElLead() throws Exception {
    String zone = "Zona CancelReason Cuatro";
    Provider provider = createPlomero("Plomero Con Motivo", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"cancelReason\":\"precio\",\"cancelReasonDetail\":\"pidió mucho más que la tarifa\"}"))
        .andExpect(status().isOk());

    Lead released = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(released.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(released.getAssignedProviderId()).isNull();

    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.PROVIDER_RELEASED_EVENT_TYPE))
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getMessage()).contains("precio");
          assertThat(event.getMessage()).contains("pidió mucho más que la tarifa");
        });
  }

  @Test
  void cancelarUnTrabajoYaCompletadoSigueExigiendoMotivo() throws Exception {
    // Corrección administrativa (antes ya completado): no resucita el
    // pedido, pero el motivo se sigue exigiendo — mismo endpoint, misma regla.
    String zone = "Zona CancelReason Cinco";
    Provider provider = createPlomero("Plomero Post Completado", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"COMPLETED\",\"amountCharged\":100.00}"))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"cancelReason\":\"otro\"}"))
        .andExpect(status().isOk());

    Lead finalState = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(finalState.getStatus()).isEqualTo(LeadStatus.CANCELLED);
    // Corrección administrativa: no revive el pedido ni le busca otro proveedor.
    assertThat(finalState.getAssignedProviderId()).isEqualTo(provider.getId());
  }

  /**
   * TelegramNotifyService.notifyProviderCancelled se llama de forma
   * best-effort (catch-all) — sin credenciales configuradas en test es un
   * no-op silencioso, así que lo que se verifica acá es que la cancelación
   * con motivo no rompe el flujo aunque Telegram esté deshabilitado (mismo
   * criterio que el resto de los avisos best-effort de la clase).
   */
  @Test
  void cancelarConMotivoNoRompeAunqueTelegramEsteDeshabilitado() throws Exception {
    String zone = "Zona CancelReason Seis";
    Provider provider = createPlomero("Plomero Telegram Off", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"cancelReason\":\"zona\"}"))
        .andExpect(status().isOk());
  }
}
