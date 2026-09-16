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
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadAgentService;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.MatchingWatchdogScheduler;
import com.fixy.backend.service.ProviderCatalogService;
import com.fixy.backend.service.ProviderSelfService;
import com.fixy.backend.service.PushNotificationService;
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
 * "Un solo scheduler de matching" (Refundación de Fixy, fase 2, contrato
 * §C): reemplaza a {@code MatchingStaleScheduler}, {@code
 * MatchingAutoReleaseScheduler} y {@code OrphanMatchRetryScheduler}
 * (borrados). Porta TODOS los escenarios de sus tres suites de test más los
 * nuevos del contrato (re-oferta inmediata al liberar, umbrales cortos con
 * plan, orphan que se resuelve cuando aparece un técnico).
 *
 * Mismo truco de Clock corrido al futuro que las suites viejas: los
 * createdAt de LeadEvent se fijan en @PrePersist y no se pueden retro-datar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.provider-commission-enabled=false"
})
class MatchingWatchdogSchedulerTest {

  private static final long STALE_MINUTES = 45;
  private static final long STALE_MINUTES_REMOTE_CARE = 20;
  private static final long RELEASE_HOURS = 12;
  private static final long RELEASE_HOURS_REMOTE_CARE = 4;
  private static final long ORPHAN_MAX_AGE_DAYS = 14;
  private static final long ORPHAN_RETRY_MINUTES = 60;

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ProviderLeadDeclineRepository declineRepository;
  @Autowired private ProviderCatalogService providerCatalogService;
  @Autowired private ProviderSelfService providerSelfService;
  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadTimelineService timelineService;
  @Autowired private LeadMessageService messageService;
  @Autowired private PushNotificationService pushNotificationService;
  @Autowired private TelegramNotifyService telegramNotifyService;

  private MatchingWatchdogScheduler schedulerWithClock(Clock clock) {
    return schedulerWithClockAndTelegram(clock, telegramNotifyService);
  }

  private MatchingWatchdogScheduler schedulerWithClockAndTelegram(Clock clock, TelegramNotifyService telegram) {
    return new MatchingWatchdogScheduler(
        leadRepository, leadEventRepository, providerRepository, declineRepository,
        providerCatalogService, providerSelfService, leadAgentService, timelineService,
        messageService, pushNotificationService, telegram,
        true, STALE_MINUTES, STALE_MINUTES_REMOTE_CARE, RELEASE_HOURS, RELEASE_HOURS_REMOTE_CARE,
        ORPHAN_MAX_AGE_DAYS, ORPHAN_RETRY_MINUTES, clock);
  }

  private Clock inFuture(Duration duration) {
    return Clock.fixed(Instant.now().plus(duration), ZoneOffset.UTC);
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

  private Lead makeReadyBroadcast(Lead lead) {
    lead.setDetectedCategory("plomeria");
    lead.setLocation("Solymar");
    lead.setReadyForMatching(true);
    Lead saved = leadRepository.save(lead);
    timelineService.appendEvent(saved, "MATCH_GENERATED", "system", "matching de test");
    return saved;
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

  private long staleEventsFor(Long leadId) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, "MATCHING_STALE_NOTIFIED").size();
  }

  private long staleMessagesFor(Long leadId) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> m.getText() != null && m.getText().contains("demorando más de lo normal"))
        .count();
  }

  private long goodNewsMessagesFor(Long leadId) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> m.getText() != null && m.getText().contains("Buenas noticias"))
        .count();
  }

  // ---- 1a. Contactado sin respuesta: aviso a los stale-minutes -----------

  @Test
  void avisaAOpsUnaSolaVezCuandoElTecnicoNoContestaEn15Min() throws Exception {
    String zone = "Zona Watchdog Lento";
    Provider provider = createProvider("Plomero Lento Quince", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);
    com.fixy.backend.service.TelegramNotifyService telegram =
        org.mockito.Mockito.mock(com.fixy.backend.service.TelegramNotifyService.class);

    MatchingWatchdogScheduler scheduler = schedulerWithClockAndTelegram(inFuture(Duration.ofMinutes(16)), telegram);
    scheduler.processOnce();

    // Solo ops: a los 15 min el cliente todavía no recibe el mensaje de stale.
    org.mockito.Mockito.verify(telegram, org.mockito.Mockito.times(1))
        .notifyProviderSlow(org.mockito.ArgumentMatchers.argThat(l -> l.getId().equals(lead.getId())),
            org.mockito.ArgumentMatchers.eq("Plomero Lento Quince"), org.mockito.ArgumentMatchers.longThat(m -> m >= 15 && m <= 17));
    assertThat(staleMessagesFor(lead.getId())).isEqualTo(0);
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "PROVIDER_SLOW_NOTIFIED")).hasSize(1);

    // Idempotencia: el segundo ciclo no vuelve a avisar.
    scheduler.processOnce();
    // Filtrado por lead: la H2 compartida entre tests puede tener otros
    // contactados viejos que también disparan su propio aviso (una vez cada uno).
    org.mockito.Mockito.verify(telegram, org.mockito.Mockito.times(1))
        .notifyProviderSlow(org.mockito.ArgumentMatchers.argThat(l -> l.getId().equals(lead.getId())),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void notificaUnaSolaVezAlSuperarElUmbralDeStale() throws Exception {
    String zone = "Zona Watchdog Stale Uno";
    Provider provider = createProvider("Plomero Silencioso Stale", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);

    MatchingWatchdogScheduler scheduler = schedulerWithClock(inFuture(Duration.ofMinutes(50)));
    scheduler.processOnce();

    assertThat(staleEventsFor(lead.getId())).isEqualTo(1);
    assertThat(staleMessagesFor(lead.getId())).isEqualTo(1);

    // Idempotencia: un segundo ciclo no duplica ni el evento ni el mensaje
    // (todavía no llegó a las release-hours).
    scheduler.processOnce();
    assertThat(staleEventsFor(lead.getId())).isEqualTo(1);
    assertThat(staleMessagesFor(lead.getId())).isEqualTo(1);
  }

  @Test
  void noNotificaAntesDelUmbralDeStale() throws Exception {
    String zone = "Zona Watchdog Stale Dos";
    Provider provider = createProvider("Plomero Rapido Stale", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);

    // Clock real: el PROVIDER_CONTACTED tiene segundos de vida, no 45 min.
    schedulerWithClock(Clock.systemUTC()).processOnce();

    assertThat(staleEventsFor(lead.getId())).isZero();
    assertThat(staleMessagesFor(lead.getId())).isZero();
  }

  @Test
  void ignoraLeadsSmokeEnElFrenteDeContactado() throws Exception {
    String zone = "Zona Watchdog Stale Tres";
    Provider provider = createProvider("Plomero Smoke Stale", zone);
    Lead lead = createChatLead();
    lead.setProblem("[smoke] prueba sintética");
    Lead contacted = contactProvider(lead, provider, zone);

    schedulerWithClock(inFuture(Duration.ofMinutes(50))).processOnce();

    assertThat(staleEventsFor(contacted.getId())).isZero();
  }

  @Test
  void umbralDeStaleEsMasCortoConPlanCasaADistancia() throws Exception {
    String zone = "Zona Watchdog Stale Plan";
    Provider provider = createProvider("Plomero Plan Stale", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);
    lead.setRemoteCarePlanId(999L);
    leadRepository.save(lead);

    // 25 min: por debajo del umbral normal (45) pero por encima del de plan (20).
    schedulerWithClock(inFuture(Duration.ofMinutes(25))).processOnce();

    assertThat(staleEventsFor(lead.getId())).isEqualTo(1);
  }

  // ---- 1b. Contactado sin respuesta: release a las release-hours ---------

  @Test
  void reOfreceEnElActoAlSegundoTecnicoAlLiberar() throws Exception {
    String zone = "Zona Watchdog Release Reoferta";
    Provider first = createProvider("Plomero Uno Reoferta", zone);
    Provider second = createProvider("Plomero Dos Reoferta", zone);
    Lead lead = contactProvider(createChatLead(), first, zone);

    schedulerWithClock(inFuture(Duration.ofHours(13))).processOnce();

    Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
    // Re-ofrecido de una: NUNCA pasa por "pozo abierto" (no vuelve a NEW).
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(reloaded.getAssignedProviderId()).isEqualTo(second.getId());

    // El decline del primero SÍ se registra (contrato §C.1.1.b: siempre,
    // antes de mirar si hay alternativa).
    assertThat(declineRepository.existsByLeadIdAndProviderId(lead.getId(), first.getId())).isTrue();
    // No hay AUTO_RELEASED: el pedido nunca quedó en pozo abierto.
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE))
        .isEmpty();
    // Dos PROVIDER_CONTACTED: el original + el de la re-oferta.
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "PROVIDER_CONTACTED"))
        .hasSize(2);
  }

  @Test
  void liberaAlPozoAbiertoCuandoNoHayAlternativaYQuedaAbiertoParaOtro() throws Exception {
    String zone = "Zona Watchdog Release Pozo";
    Provider provider = createProvider("Plomero Unico Pozo", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);

    schedulerWithClock(inFuture(Duration.ofHours(13))).processOnce();

    Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(reloaded.getAssignedProviderId()).isNull();
    assertThat(reloaded.getAssignedProvider()).isNull();
    assertThat(reloaded.isReadyForMatching()).isTrue();

    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE))
        .hasSize(1);

    // A diferencia de fase 1: el decline del único contactado SÍ se
    // registra (contrato §C.1.1.b, siempre antes de re-ofrecer) — deviation
    // deliberada respecto del viejo MatchingAutoReleaseScheduler (que NO
    // registraba decline en el pozo abierto), documentada en el contrato.
    assertThat(declineRepository.existsByLeadIdAndProviderId(lead.getId(), provider.getId())).isTrue();
  }

  @Test
  void unaSegundaCorridaNoVuelveALiberarloEnElMismoCiclo() throws Exception {
    String zone = "Zona Watchdog Release Idempotente";
    Provider provider = createProvider("Plomero Repetido Idempotente", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);

    MatchingWatchdogScheduler scheduler = schedulerWithClock(inFuture(Duration.ofHours(13)));
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
    String zone = "Zona Watchdog Release Aceptado";
    Provider provider = createProvider("Plomero Acepto", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\"}"))
        .andExpect(status().isOk());

    schedulerWithClock(inFuture(Duration.ofHours(13))).processOnce();

    Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.ASSIGNED);
    assertThat(reloaded.getAssignedProviderId()).isEqualTo(provider.getId());
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE))
        .isEmpty();
  }

  @Test
  void noLiberaLeadsSmokeEnRelease() throws Exception {
    String zone = "Zona Watchdog Release Smoke";
    Provider provider = createProvider("Plomero Smoke Release", zone);
    Lead lead = createChatLead();
    lead.setProblem("[smoke] prueba sintética");
    Lead contacted = contactProvider(lead, provider, zone);

    schedulerWithClock(inFuture(Duration.ofHours(13))).processOnce();

    Lead reloaded = leadRepository.findById(contacted.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(reloaded.getAssignedProviderId()).isEqualTo(provider.getId());
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            contacted.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE))
        .isEmpty();
  }

  @Test
  void resumenDeTelegramEsUnoSoloPorCorridaAunqueLibereVarios() throws Exception {
    String zoneA = "Zona Watchdog Release Throttle A";
    String zoneB = "Zona Watchdog Release Throttle B";
    Provider providerA = createProvider("Plomero Silencioso Throttle A", zoneA);
    Provider providerB = createProvider("Plomero Silencioso Throttle B", zoneB);
    Lead leadA = contactProvider(createChatLead(), providerA, zoneA);
    Lead leadB = contactProvider(createChatLead(), providerB, zoneB);

    TelegramNotifyService telegramMock = mock(TelegramNotifyService.class);
    schedulerWithClockAndTelegram(inFuture(Duration.ofHours(13)), telegramMock).processOnce();

    // Un único resumen para toda la corrida, con ambos leads adentro (puede
    // incluir además otros leads sobrantes de otros tests del mismo
    // contexto compartido).
    verify(telegramMock, times(1)).notifyAutoReleaseSummary(
        org.mockito.ArgumentMatchers.argThat(list ->
            list.stream().map(Lead::getId).toList().containsAll(List.of(leadA.getId(), leadB.getId()))),
        eq(RELEASE_HOURS));
  }

  @Test
  void umbralDeReleaseEsMasCortoConPlanCasaADistancia() throws Exception {
    String zone = "Zona Watchdog Release Plan";
    Provider provider = createProvider("Plomero Plan Release", zone);
    Lead lead = contactProvider(createChatLead(), provider, zone);
    lead.setRemoteCarePlanId(999L);
    leadRepository.save(lead);

    // 5h: por debajo del umbral normal (12) pero por encima del de plan (4).
    schedulerWithClock(inFuture(Duration.ofHours(5))).processOnce();

    Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.AUTO_RELEASED_EVENT_TYPE))
        .hasSize(1);
  }

  // ---- 2. Huérfanos --------------------------------------------------------

  @Test
  void reintentaCuandoApareceProveedorYNoLoRepite() throws Exception {
    String zone = "Solymar Watchdog Huerfano Uno";
    Lead lead = makeOrphanWaiting(zone);
    MatchingWatchdogScheduler scheduler = schedulerWithClock(Clock.systemUTC());

    // Todavía no hay proveedor en esa zona: el job avisa UNA vez (mensaje
    // honesto + evento MATCH_BLOCKED) y no repite en corridas siguientes.
    scheduler.processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "MATCH_BLOCKED"))
        .hasSize(1);
    scheduler.processOnce();
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "MATCH_BLOCKED"))
        .hasSize(1);

    // Aparece el proveedor (el caso real: Carlos da de alta a alguien días
    // después de que el pedido entró) — el reintento silencioso lo encuentra.
    Provider provider = createPlomeroIn(zone);

    scheduler.processOnce();

    Lead matched = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(matched.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(matched.getAssignedProviderId()).isEqualTo(provider.getId());

    // Un segundo ciclo ya no lo toca: tiene proveedor asignado.
    scheduler.processOnce();
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "PROVIDER_CONTACTED"))
        .hasSize(1);
  }

  @Test
  void primerIntentoContactaSinNecesitarMatchBlockedSiYaHayProveedor() throws Exception {
    String zone = "Solymar Watchdog Huerfano Directo";
    Lead lead = makeOrphanWaiting(zone);
    Provider provider = createPlomeroIn(zone);

    schedulerWithClock(Clock.systemUTC()).processOnce();

    Lead matched = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(matched.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(matched.getAssignedProviderId()).isEqualTo(provider.getId());
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "MATCH_BLOCKED"))
        .isEmpty();
  }

  @Test
  void ignoraLeadsSmokeEnHuerfanos() throws Exception {
    String zone = "Solymar Watchdog Huerfano Dos";
    Lead lead = makeOrphanWaiting(zone);
    lead.setProblem("[smoke] Pedido de plomería");
    leadRepository.save(lead);
    createPlomeroIn(zone);

    schedulerWithClock(Clock.systemUTC()).processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
  }

  @Test
  void ignoraLeadsQueTuvieronContactoReciente() throws Exception {
    // PROVIDER_CONTACTED hace 10 min (< orphan-retry-minutes 60): todavía no
    // es territorio de huérfanos, aunque haya vuelto a NEW mientras tanto.
    String zone = "Solymar Watchdog Huerfano Tres";
    Lead lead = makeOrphanWaiting(zone);
    timelineService.appendEvent(lead, "PROVIDER_CONTACTED", "system", "contacto reciente de test");
    createPlomeroIn(zone);

    schedulerWithClock(Clock.systemUTC()).processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "PROVIDER_CONTACTED"))
        .hasSize(1);
  }

  @Test
  void reintentaUnLeadConContactoViejoFueraDeLaVentanaDeRetry() throws Exception {
    // PROVIDER_CONTACTED viejo (fuera de la ventana de 60 min): sí es
    // territorio de huérfanos otra vez (ej. liberado hace rato, contacto
    // no tan reciente) — mismo criterio que el viejo scheduler pero
    // acotado en el tiempo en vez de "para siempre".
    String zone = "Solymar Watchdog Huerfano Reintento";
    Lead lead = makeOrphanWaiting(zone);
    timelineService.appendEvent(lead, "PROVIDER_CONTACTED", "system", "contacto viejo de test");
    Provider provider = createPlomeroIn(zone);

    schedulerWithClock(inFuture(Duration.ofMinutes(90))).processOnce();

    Lead matched = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(matched.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(matched.getAssignedProviderId()).isEqualTo(provider.getId());
  }

  @Test
  void ignoraPedidosFueraDeLaVentanaDeDemandaViva() throws Exception {
    String zone = "Solymar Watchdog Huerfano Cuatro";
    Lead lead = makeOrphanWaiting(zone);
    createPlomeroIn(zone);

    Clock inThirtyDays = inFuture(Duration.ofDays(30));
    schedulerWithClock(inThirtyDays).processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
  }

  @Test
  void ignoraLeadsTodaviaEnIntake() throws Exception {
    // Sin categoría/zona resueltas (readyForMatching=false): no es huérfano
    // ni contactado — el watchdog no lo toca.
    Lead lead = createChatLead();

    schedulerWithClock(inFuture(Duration.ofHours(1))).processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
  }

  @Test
  void ignoraLeadsSmokeDeBroadcastEnElFrenteDeStale() throws Exception {
    Lead lead = createChatLead();
    lead.setProblem("[smoke] prueba sintética");
    makeReadyBroadcast(lead);

    schedulerWithClock(inFuture(Duration.ofHours(1))).processOnce();

    assertThat(staleEventsFor(lead.getId())).isZero();
  }

  private Lead makeOrphanWaiting(String zone) throws Exception {
    Lead lead = createChatLead();
    lead.setProblem("Pedido de plomería");
    lead.setDetectedCategory("plomeria");
    lead.setLocation(zone);
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.NEW);
    return leadRepository.save(lead);
  }

  private Provider createPlomeroIn(String zone) {
    Provider provider = new Provider();
    provider.setName("Plomero Watchdog de " + zone);
    provider.setPhone("099000111");
    provider.setCategories("plomeria");
    provider.setPrimaryZone(zone);
    provider.setStatus(ProviderStatus.AVAILABLE);
    return providerRepository.save(provider);
  }
}
