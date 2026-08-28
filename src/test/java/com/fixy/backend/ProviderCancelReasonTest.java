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
 * Motivo obligatorio al cancelar (mejora 2026-08-19): cancelar un trabajo
 * YA ACEPTADO desde el panel del proveedor sin decir por qué no avisaba a
 * nadie y no dejaba rastro del motivo.
 *
 * Ajuste 2026-08-19 (choque con el frontend real): el botón "No me sirve"
 * del momento accept-decide (lead auto-matcheado en PROVIDER_CONTACTED,
 * TODAVÍA sin aceptar) pega al MISMO endpoint {@code /leads/{id}/status}
 * con {@code status:CANCELLED} y a propósito NO manda motivo — declinar es
 * de bajo compromiso (pasa todo el tiempo), igual que declinar desde la
 * bandeja. El discriminador de "obligatorio" es el status DEL LEAD ANTES de
 * este CANCELLED (ver {@code ProviderSelfService.COMMITTED_STATUSES_BEFORE_CANCEL}):
 * ASSIGNED / IN_PROGRESS / COMPLETED exigen motivo (y avisan a Telegram);
 * PROVIDER_CONTACTED (todavía no aceptado) no exige nada, aunque si el
 * motivo viene igual se persiste gratis.
 *
 * Contrato con el frontend: {@code cancelReason}
 * ("sin_disponibilidad"|"zona"|"precio"|"otro"), {@code cancelReasonDetail}
 * libre opcional, máx 300 caracteres (400 si se excede, sin importar si el
 * motivo era obligatorio o no).
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

  /** Auto-match: proveedor contactado, TODAVÍA sin aceptar (accept-decide moment). */
  private Lead contactProvider(Lead lead, Provider provider) {
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    return leadRepository.save(lead);
  }

  /** El proveedor acepta desde el panel — a partir de acá está "comprometido". */
  private void acceptAsProvider(Provider provider, Long leadId) throws Exception {
    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), leadId)
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void cancelarUnTrabajoYaAceptadoSinMotivoDevuelve400YNoTocaElLead() throws Exception {
    String zone = "Zona CancelReason Uno";
    Provider provider = createPlomero("Plomero Sin Motivo", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);
    acceptAsProvider(provider, lead.getId());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isBadRequest());

    Lead untouched = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(untouched.getStatus()).isEqualTo(LeadStatus.ASSIGNED);
    assertThat(untouched.getAssignedProviderId()).isEqualTo(provider.getId());
  }

  @Test
  void cancelarUnTrabajoYaAceptadoConMotivoVacioDevuelve400() throws Exception {
    String zone = "Zona CancelReason Dos";
    Provider provider = createPlomero("Plomero Motivo Vacio", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);
    acceptAsProvider(provider, lead.getId());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"cancelReason\":\"   \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cancelarConDetalleDemasiadoLargoDevuelve400_sinImportarSiElMotivoEraObligatorio() throws Exception {
    // Lead todavía SIN aceptar (PROVIDER_CONTACTED): el motivo no sería
    // obligatorio, pero la validación de largo del detalle igual aplica.
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
  void cancelarUnTrabajoYaAceptadoConMotivoLoPersisteEnElEventoExistenteYLiberaElLead() throws Exception {
    String zone = "Zona CancelReason Cuatro";
    Provider provider = createPlomero("Plomero Con Motivo", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);
    acceptAsProvider(provider, lead.getId());

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
          assertThat(event.getMessage()).contains("canceló");
          assertThat(event.getMessage()).contains("precio");
          assertThat(event.getMessage()).contains("pidió mucho más que la tarifa");
        });
  }

  @Test
  void cancelarUnTrabajoYaCompletadoSigueExigiendoMotivo() throws Exception {
    // Corrección administrativa (antes ya completado): no resucita el
    // pedido, pero el motivo se sigue exigiendo — COMPLETED es un status
    // comprometido igual que ASSIGNED/IN_PROGRESS.
    String zone = "Zona CancelReason Cinco";
    Provider provider = createPlomero("Plomero Post Completado", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);
    acceptAsProvider(provider, lead.getId());

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
   * criterio que el resto de los avisos best-effort de la clase). El
   * disparo REAL a Telegram (solo en el caso ya aceptado, nunca en el
   * decline pre-aceptación) está cubierto con el mock HTTP en
   * TelegramNotifyServiceTest.
   */
  @Test
  void cancelarConMotivoNoRompeAunqueTelegramEsteDeshabilitado() throws Exception {
    String zone = "Zona CancelReason Seis";
    Provider provider = createPlomero("Plomero Telegram Off", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);
    acceptAsProvider(provider, lead.getId());

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"cancelReason\":\"zona\"}"))
        .andExpect(status().isOk());
  }

  /**
   * El caso que motivó el ajuste: "No me sirve" en el momento accept-decide
   * (lead auto-matcheado, TODAVÍA en PROVIDER_CONTACTED — el proveedor
   * nunca aceptó) pega al mismo endpoint sin cancelReason. Debe seguir
   * funcionando como siempre: 2xx, el lead se libera al pozo abierto.
   */
  @Test
  void declinarAntesDeAceptarSinMotivoDevuelve2xxYLiberaElLeadComoSiempre() throws Exception {
    String zone = "Zona CancelReason Siete";
    Provider provider = createPlomero("Plomero Decline Sin Aceptar", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().is2xxSuccessful());

    Lead released = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(released.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(released.getAssignedProviderId()).isNull();
    assertThat(released.getAssignedProvider()).isNull();
    assertThat(released.isReadyForMatching()).isTrue();

    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.PROVIDER_RELEASED_EVENT_TYPE))
        .singleElement()
        .satisfies(event -> assertThat(event.getMessage()).contains("declinó"));
  }

  /** Si el motivo viene igual en un decline pre-aceptación (no era obligatorio), se persiste gratis. */
  @Test
  void declinarAntesDeAceptarConMotivoOpcionalLoPersisteIgual() throws Exception {
    String zone = "Zona CancelReason Ocho";
    Provider provider = createPlomero("Plomero Decline Con Motivo", zone);
    Lead lead = contactProvider(makeOrphanWaiting(zone), provider);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"cancelReason\":\"zona\"}"))
        .andExpect(status().is2xxSuccessful());

    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.PROVIDER_RELEASED_EVENT_TYPE))
        .singleElement()
        .satisfies(event -> assertThat(event.getMessage()).contains("zona"));
  }
}
