package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadAgentService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.OrphanMatchRetryScheduler;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * "La promesa que nadie cumplía": un pedido que quedó listo cuando NO había
 * proveedor en su categoría/zona debe volver a intentarse cuando aparece uno,
 * en vez de esperar para siempre (5 pedidos reales así en prod al 2026-07-29).
 *
 * Cada test usa una zona propia e inexistente en el resto de la suite para
 * que el catálogo compartido de H2 no meta matches de otros tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.enabled=false"
})
class OrphanMatchRetrySchedulerTest {

  private static final long MAX_AGE_DAYS = 14;

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadTimelineService timelineService;

  private OrphanMatchRetryScheduler schedulerWithClock(Clock clock) {
    return new OrphanMatchRetryScheduler(
        leadRepository, leadEventRepository, leadAgentService, true, MAX_AGE_DAYS, clock);
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

  /** Lead listo para matching, sin proveedor y sin ningún evento de matching: el huérfano. */
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
    provider.setName("Plomero de " + zone);
    provider.setPhone("099000111");
    provider.setCategories("plomeria");
    provider.setPrimaryZone(zone);
    provider.setStatus(ProviderStatus.AVAILABLE);
    return providerRepository.save(provider);
  }

  private long goodNewsMessagesFor(Long leadId) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> m.getText() != null && m.getText().contains("Buenas noticias"))
        .count();
  }

  @Test
  void reintentaCuandoApareceProveedorYNoLoRepite() throws Exception {
    String zone = "Solymar Huerfano Uno";
    Lead lead = makeOrphanWaiting(zone);
    OrphanMatchRetryScheduler scheduler = schedulerWithClock(Clock.systemUTC());

    // Todavía no hay proveedor en esa zona: el job no hace ruido y el
    // cliente NO recibe un segundo mensaje de "no hay proveedores".
    scheduler.processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(goodNewsMessagesFor(lead.getId())).isZero();

    // Aparece el proveedor (el caso real: Carlos da de alta a alguien días
    // después de que el pedido entró).
    Provider provider = createPlomeroIn(zone);

    scheduler.processOnce();

    Lead matched = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(matched.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(matched.getAssignedProviderId()).isEqualTo(provider.getId());
    assertThat(goodNewsMessagesFor(lead.getId())).isEqualTo(1);
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "PROVIDER_CONTACTED"))
        .singleElement()
        .satisfies(event -> assertThat(event.getMessage()).contains("Reintento de matching"));

    // Un segundo ciclo ya no lo toca: tiene proveedor asignado.
    scheduler.processOnce();
    assertThat(goodNewsMessagesFor(lead.getId())).isEqualTo(1);
  }

  @Test
  void ignoraLeadsSmoke() throws Exception {
    String zone = "Solymar Huerfano Dos";
    Lead lead = makeOrphanWaiting(zone);
    lead.setProblem("[smoke] Pedido de plomería");
    leadRepository.save(lead);
    createPlomeroIn(zone);

    schedulerWithClock(Clock.systemUTC()).processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
  }

  @Test
  void ignoraLeadsQueYaEntraronAMatching() throws Exception {
    // Con MATCH_GENERATED el pedido ya es visible en las bandejas: si nadie
    // lo toma es asunto de MatchingStaleScheduler, no de este job.
    String zone = "Solymar Huerfano Tres";
    Lead lead = makeOrphanWaiting(zone);
    timelineService.appendEvent(lead, "MATCH_GENERATED", "system", "broadcast de test");
    createPlomeroIn(zone);

    schedulerWithClock(Clock.systemUTC()).processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
  }

  @Test
  void ignoraPedidosFueraDeLaVentanaDeDemandaViva() throws Exception {
    // El createdAt lo fija @PrePersist, así que el paso del tiempo se simula
    // con el Clock corrido al futuro (mismo patrón que MatchingStaleScheduler).
    String zone = "Solymar Huerfano Cuatro";
    Lead lead = makeOrphanWaiting(zone);
    createPlomeroIn(zone);

    Clock inThirtyDays = Clock.fixed(Instant.now().plus(Duration.ofDays(30)), ZoneOffset.UTC);
    schedulerWithClock(inThirtyDays).processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
  }

  @Test
  void ignoraLeadsTodaviaEnIntake() throws Exception {
    // Sin categoría/zona resueltas (readyForMatching=false): territorio del
    // ReengagementScheduler.
    Lead lead = createChatLead();

    schedulerWithClock(Clock.systemUTC()).processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.NEW);
  }
}
