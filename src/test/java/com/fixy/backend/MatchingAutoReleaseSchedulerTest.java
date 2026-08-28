package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.MatchingAutoReleaseScheduler;
import com.fixy.backend.service.ProviderCatalogService;
import com.fixy.backend.service.ProviderSelfService;
import com.fixy.backend.service.PushNotificationService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.TelegramNotifyService;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Diagnóstico 2026-08-17: el auto-match asigna en EXCLUSIVA y, mientras el
 * proveedor contactado no responde, la bandeja pública lo esconde del resto
 * — resultado real en prod: ~18 leads sin aceptar en 4 días.
 * {@link MatchingAutoReleaseScheduler} libera al pozo abierto tras el
 * umbral. Mismo truco de Clock corrido al futuro que
 * {@code MatchingStaleSchedulerTest} (los createdAt de LeadEvent no se
 * pueden retro-datar).
 *
 * Nota sobre aserciones: igual que {@code ProviderCancelReleasesLeadTest},
 * este contexto comparte H2 con otras clases con las mismas properties —
 * las aserciones se hacen sobre el lead/evento puntual del test, nunca
 * sobre el conteo AGREGADO de {@code processOnce()}, que puede incluir
 * sobrantes de otros tests corridos en el mismo contexto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.enabled=false"
})
class MatchingAutoReleaseSchedulerTest {

  private static final long THRESHOLD_HOURS = 12;

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ProviderLeadDeclineRepository declineRepository;
  @Autowired private ProviderCatalogService providerCatalogService;
  @Autowired private ProviderSelfService providerSelfService;
  @Autowired private LeadTimelineService timelineService;
  @Autowired private PushNotificationService pushNotificationService;
  @Autowired private TelegramNotifyService telegramNotifyService;

  private MatchingAutoReleaseScheduler schedulerWithClock(Clock clock) {
    return schedulerWithClockAndTelegram(clock, telegramNotifyService);
  }

  private MatchingAutoReleaseScheduler schedulerWithClockAndTelegram(Clock clock, TelegramNotifyService telegram) {
    return new MatchingAutoReleaseScheduler(
        leadRepository, leadEventRepository, providerRepository, providerCatalogService,
        providerSelfService, pushNotificationService, telegram,
        true, THRESHOLD_HOURS, clock);
  }

  /** Clock corrido 13h al futuro: cualquier PROVIDER_CONTACTED recién creado ya "cumplió" las 12h. */
  private Clock inThirteenHours() {
    return Clock.fixed(Instant.now().plus(Duration.ofHours(13)), ZoneOffset.UTC);
  }

  private Lead createChatLead() throws Exception {
    MvcResult res = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer id = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    return leadRepository.findById(Long.valueOf(id)).orElseThrow();
  }

  private Provider createProvider(String name, String zone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("099000222");
    provider.setCategories("plomeria");
    provider.setPrimaryZone(zone);
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setAccessToken("token-" + name.replace(' ', '-'));
    return providerRepository.save(provider);
  }

  /** Deja el lead en PROVIDER_CONTACTED con el proveedor dado, como hace LeadAgentService.contactTopMatch. */
  private Lead contactProvider(Lead lead, Provider provider, String zone) {
    lead.setDetectedCategory("plomeria");
    lead.setLocation(zone);
    lead.setReadyForMatching(true);
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    Lead saved = leadRepository.save(lead);
    timelineService.appendEvent(saved, "PROVIDER_CONTACTED", "system", "Contactando a " + provider.getName());
    return saved;
  }

  @Test
  void liberaElLeadTrasElUmbralYQuedaAbiertoParaOtro() throws Exception {
    String zone = "Zona AutoRelease Uno";
    Provider provider = createProvider("Plomero Silencioso", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);

    schedulerWithClock(inThirteenHours()).processOnce();

    Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(reloaded.getAssignedProviderId()).isNull();
    assertThat(reloaded.getAssignedProvider()).isNull();
    assertThat(reloaded.isReadyForMatching()).isTrue();

    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE))
        .hasSize(1);

    // El pozo queda ABIERTO: a diferencia de una cancelación activa, NO se
    // registra decline — el proveedor que no respondió puede volver a verlo.
    assertThat(declineRepository.existsByLeadIdAndProviderId(lead.getId(), provider.getId())).isFalse();
  }

  @Test
  void unaSegundaCorridaNoVuelveALiberarloEnElMismoCiclo() throws Exception {
    String zone = "Zona AutoRelease Cinco";
    Provider provider = createProvider("Plomero Repetido", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);

    MatchingAutoReleaseScheduler scheduler = schedulerWithClock(inThirteenHours());
    scheduler.processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);

    // Ya liberado (status volvió a NEW, ya no PROVIDER_CONTACTED): una
    // segunda corrida no lo vuelve a tocar ni duplica su evento.
    scheduler.processOnce();
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE))
        .hasSize(1);
  }

  @Test
  void noLiberaUnLeadQueElProveedorYaAcepto() throws Exception {
    String zone = "Zona AutoRelease Dos";
    Provider provider = createProvider("Plomero Rapido", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\"}"))
        .andExpect(status().isOk());

    schedulerWithClock(inThirteenHours()).processOnce();

    Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.ASSIGNED);
    assertThat(reloaded.getAssignedProviderId()).isEqualTo(provider.getId());
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE))
        .isEmpty();
  }

  @Test
  void noLiberaLeadsSmoke() throws Exception {
    String zone = "Zona AutoRelease Tres";
    Provider provider = createProvider("Plomero Smoke", zone);
    Lead lead = createChatLead();
    lead.setProblem("[smoke] prueba sintética");
    Lead contacted = contactProvider(lead, provider, zone);

    schedulerWithClock(inThirteenHours()).processOnce();

    Lead reloaded = leadRepository.findById(contacted.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(reloaded.getAssignedProviderId()).isEqualTo(provider.getId());
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            contacted.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE))
        .isEmpty();
  }

  /**
   * Throttle "por corrida": si el scheduler libera varios leads de una vez,
   * ops recibe UN resumen (no uno por lead). TelegramNotifyService se
   * mockea acá para verificar el conteo de invocaciones sin depender de red
   * — el contenido real del texto ya lo cubre TelegramNotifyServiceTest.
   */
  @Test
  void resumenDeTelegramEsUnoSoloPorCorridaAunqueLibereVarios() throws Exception {
    String zoneA = "Zona AutoRelease Throttle A";
    String zoneB = "Zona AutoRelease Throttle B";
    Provider providerA = createProvider("Plomero Silencioso A", zoneA);
    Provider providerB = createProvider("Plomero Silencioso B", zoneB);
    Lead leadA = contactProvider(createChatLead(), providerA, zoneA);
    Lead leadB = contactProvider(createChatLead(), providerB, zoneB);

    TelegramNotifyService telegramMock = mock(TelegramNotifyService.class);
    schedulerWithClockAndTelegram(inThirteenHours(), telegramMock).processOnce();

    // Un único resumen para toda la corrida, con ambos leads adentro (puede
    // incluir además otros leads sobrantes de otros tests del mismo
    // contexto compartido — no se exige tamaño exacto, solo que llegó UNA
    // vez y que incluye a los dos de este test).
    verify(telegramMock, times(1)).notifyAutoReleaseSummary(
        org.mockito.ArgumentMatchers.argThat(list ->
            list.stream().map(Lead::getId).toList().containsAll(List.of(leadA.getId(), leadB.getId()))),
        eq(THRESHOLD_HOURS));
  }
}
