package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderLeadDecline;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadAgentService;
import com.fixy.backend.service.OrphanMatchRetryScheduler;
import com.fixy.backend.service.ProviderSelfService;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Los dos bugs que la guardia del 2026-07-29 encontró en prod, juntos porque
 * en vivo se dispararon juntos:
 *
 * 1. El reintento de matching le ofreció los leads #128/#135/#147 a Carnot
 *    Clima, que ya los había declinado el 23/07 — el matching automático no
 *    miraba la tabla de rechazos que la bandeja sí respetaba.
 * 2. Cuando Carnot los rechazó de nuevo, los pedidos quedaron CANCELLED y en
 *    silencio: el cliente había leído "¡Buenas noticias! Apareció un
 *    proveedor" 17 minutos antes y nunca supo más nada.
 *
 * Cada test usa una zona propia e inexistente en el resto de la suite para que
 * el catálogo compartido de H2 no meta matches de otros tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.enabled=false"
})
class ProviderCancelReleasesLeadTest {

  private static final long MAX_AGE_DAYS = 14;

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ProviderLeadDeclineRepository declineRepository;
  @Autowired private LeadAgentService leadAgentService;

  private OrphanMatchRetryScheduler scheduler() {
    return new OrphanMatchRetryScheduler(
        leadRepository, leadEventRepository, leadAgentService, true, MAX_AGE_DAYS, Clock.systemUTC());
  }

  private Lead makeOrphanWaiting(String zone) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer id = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    Lead lead = leadRepository.findById(Long.valueOf(id)).orElseThrow();
    lead.setProblem("Pedido de plomería");
    lead.setDetectedCategory("plomeria");
    lead.setLocation(zone);
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.NEW);
    return leadRepository.save(lead);
  }

  private Provider createPlomero(String name, String zone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("099000111");
    provider.setCategories("plomeria");
    provider.setPrimaryZone(zone);
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setAccessToken("token-" + name.replace(' ', '-'));
    return providerRepository.save(provider);
  }

  private void cancelAsProvider(Provider provider, Long leadId) throws Exception {
    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), leadId)
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().is2xxSuccessful());
  }

  private long messagesContaining(Long leadId, String fragment) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> m.getText() != null && m.getText().contains(fragment))
        .count();
  }

  /** BUG 2: el rechazo del proveedor no puede matar el pedido del cliente. */
  @Test
  void cancelarLiberaElPedidoYSeLoDiceAlCliente() throws Exception {
    String zone = "Solymar Cancelacion Uno";
    Lead lead = makeOrphanWaiting(zone);
    Provider provider = createPlomero("Plomero Cancelador", zone);
    scheduler().processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus())
        .isEqualTo(LeadStatus.PROVIDER_CONTACTED);

    cancelAsProvider(provider, lead.getId());

    Lead released = leadRepository.findById(lead.getId()).orElseThrow();
    // El pedido sigue vivo y sin dueño, listo para que lo tome otro.
    assertThat(released.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(released.getAssignedProviderId()).isNull();
    assertThat(released.getAssignedProvider()).isNull();
    assertThat(released.isReadyForMatching()).isTrue();

    // El rechazo queda registrado (mismo registro que respeta la bandeja).
    assertThat(declineRepository.existsByLeadIdAndProviderId(lead.getId(), provider.getId())).isTrue();

    // Y el cliente se entera, que era lo que faltaba.
    assertThat(messagesContaining(lead.getId(), "no va a poder tomar tu pedido")).isEqualTo(1);
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
            lead.getId(), ProviderSelfService.PROVIDER_RELEASED_EVENT_TYPE))
        .singleElement()
        .satisfies(event -> assertThat(event.getMessage()).contains("vuelve a búsqueda"));
  }

  /** BUG 1 + BUG 2: liberado, el reintento le busca OTRO, no el que canceló. */
  @Test
  void despuesDeCancelarElReintentoBuscaOtroProveedor() throws Exception {
    String zone = "Solymar Cancelacion Dos";
    Lead lead = makeOrphanWaiting(zone);
    Provider primero = createPlomero("Plomero Primero", zone);
    scheduler().processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getAssignedProviderId())
        .isEqualTo(primero.getId());

    cancelAsProvider(primero, lead.getId());
    Provider segundo = createPlomero("Plomero Segundo", zone);

    scheduler().processOnce();

    Lead rematched = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(rematched.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(rematched.getAssignedProviderId()).isEqualTo(segundo.getId());
  }

  /**
   * BUG 1, el caso literal de prod: el único proveedor de la zona ya rechazó
   * este pedido. Antes el reintento se lo volvía a ofrecer en cada ciclo y le
   * prometía al cliente un proveedor que ya había dicho que no.
   */
  @Test
  void noReofreceElPedidoAlProveedorQueYaLoRechazo() throws Exception {
    String zone = "Solymar Cancelacion Tres";
    Lead lead = makeOrphanWaiting(zone);
    Provider unico = createPlomero("Plomero Unico", zone);
    scheduler().processOnce();
    cancelAsProvider(unico, lead.getId());

    // Dos ciclos más: no hay a quién ofrecerlo y el cliente no recibe una
    // segunda falsa buena noticia.
    scheduler().processOnce();
    scheduler().processOnce();

    Lead waiting = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(waiting.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(waiting.getAssignedProviderId()).isNull();
    assertThat(messagesContaining(lead.getId(), "Buenas noticias")).isEqualTo(1);
  }

  /**
   * Cancelar un trabajo YA COMPLETADO es una corrección administrativa, no un
   * rechazo: no corresponde resucitar el pedido ni buscarle otro proveedor.
   */
  @Test
  void cancelarUnTrabajoCompletadoNoResucitaElPedido() throws Exception {
    String zone = "Solymar Cancelacion Cinco";
    Lead lead = makeOrphanWaiting(zone);
    Provider provider = createPlomero("Plomero Completador", zone);
    scheduler().processOnce();

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"COMPLETED\"}"))
        .andExpect(status().is2xxSuccessful());

    cancelAsProvider(provider, lead.getId());

    Lead cancelled = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(cancelled.getStatus()).isEqualTo(LeadStatus.CANCELLED);
    assertThat(messagesContaining(lead.getId(), "no va a poder tomar tu pedido")).isZero();
  }

  /** Un decline desde la bandeja pesa igual que una cancelación. */
  @Test
  void respetaElDeclineDeLaBandejaAlReintentar() throws Exception {
    String zone = "Solymar Cancelacion Cuatro";
    Lead lead = makeOrphanWaiting(zone);
    Provider provider = createPlomero("Plomero Declinador", zone);

    ProviderLeadDecline decline = new ProviderLeadDecline();
    decline.setLeadId(lead.getId());
    decline.setProviderId(provider.getId());
    declineRepository.save(decline);

    scheduler().processOnce();

    Lead untouched = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(untouched.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(untouched.getAssignedProviderId()).isNull();
    assertThat(messagesContaining(lead.getId(), "Buenas noticias")).isZero();
  }

  /**
   * Historial "no concretados" (feedback de Guillermo vía Carlos 2026-08-05):
   * al soltar un pedido, el proveedor lo ve después en su /me como
   * declinedLeads — con el desenlace — en vez de que desaparezca sin rastro.
   */
  @Test
  void elPedidoSoltadoApareceEnElHistorialDeNoConcretados() throws Exception {
    Lead lead = makeOrphanWaiting("Zona Historial Test");
    Provider provider = createPlomero("Plomero Historial", "Zona Historial Test");
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    leadRepository.save(lead);

    cancelAsProvider(provider, lead.getId());

    MvcResult me = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/api/public/providers/{pid}/me", provider.getId())
            .param("token", provider.getAccessToken()))
        .andExpect(status().isOk())
        .andReturn();
    String body = me.getResponse().getContentAsString();
    java.util.List<Integer> ids = JsonPath.read(body, "$.declinedLeads[*].leadId");
    assertThat(ids).contains(lead.getId().intValue());
    String outcome = JsonPath.read(body, "$.declinedLeads[0].outcome");
    assertThat(outcome).isEqualTo("en_busqueda");
    // Y ya no está entre los trabajos activos.
    java.util.List<Integer> activos = JsonPath.read(body, "$.assignedLeads[*].id");
    assertThat(activos).doesNotContain(lead.getId().intValue());
  }
}
