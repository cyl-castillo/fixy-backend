package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadAgentService;
import com.fixy.backend.service.OrphanMatchRetryScheduler;
import com.fixy.backend.service.TelegramNotifyService;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * "La promesa vacía cuando no queda nadie" (embudo de prod 2026-09-01).
 *
 * Los tres únicos pedidos de la semana que llegaron completos y matcheables
 * murieron igual: #255 y #256 (pastelería en Solymar) los rechazó Melissa, la
 * única pastelera del catálogo, el 27/08; #260 (aires en Solymar) lo rechazó
 * Carnot Clima, el único técnico, el 28/08. A los tres el sistema les dijo
 * "Ya estoy buscando a otra persona y te aviso apenas tenga novedades" — y no
 * había otra persona. {@code retryAutoMatch} calla a propósito cuando no hay
 * match, así que los tres siguen NEW días después sin un solo mensaje, y
 * Carlos nunca se enteró: el aviso a Telegram solo salía si el trabajo ya
 * estaba comprometido, y un decline pre-aceptación no lo está.
 *
 * Lo que se arregla: cuando el que rechaza era el último, se le dice la
 * verdad al vecino y se avisa al equipo, que es quien puede salir a captar.
 *
 * Zonas propias e inexistentes en el resto de la suite: el catálogo de H2 es
 * compartido entre tests (ver ProviderCancelReleasesLeadTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.enabled=false",
    // Los schedulers vivos de otro contexto no deben tocar estos leads.
    "fixy.orphan-match-retry.enabled=false",
    "fixy.matching-stale.enabled=false",
    "fixy.reengagement.enabled=false"
})
class ProviderDeclineWithoutSupplyTest {

  private static final String PROMESA_DE_BUSQUEDA = "Ya estoy buscando a otra persona";
  private static final String SIN_NADIE_MAS = "no tengo a nadie más";

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadAgentService leadAgentService;

  @MockitoBean private TelegramNotifyService telegramNotifyService;

  /** Instancia propia: el scheduler del contexto está apagado a propósito. */
  private OrphanMatchRetryScheduler scheduler() {
    return new OrphanMatchRetryScheduler(
        leadRepository, leadEventRepository, leadAgentService, true, 14, Clock.systemUTC());
  }

  private Lead makePasteleriaLead(String zone) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer id = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    Lead lead = leadRepository.findById(Long.valueOf(id)).orElseThrow();
    lead.setProblem("Pedido de pastelería");
    lead.setDetectedCategory("pasteleria");
    lead.setLocation(zone);
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.NEW);
    return leadRepository.save(lead);
  }

  private Provider createPastelera(String name, String zone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("099000222");
    provider.setCategories("pasteleria");
    provider.setPrimaryZone(zone);
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setAccessToken("token-" + name.replace(' ', '-'));
    return providerRepository.save(provider);
  }

  private void declineAsProvider(Provider provider, Long leadId) throws Exception {
    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), leadId)
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"CANCELLED\",\"cancelReason\":\"sin_disponibilidad\"}"))
        .andExpect(status().is2xxSuccessful());
  }

  private String lastAgentMessage(Long leadId) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .map(m -> m.getText())
        .filter(t -> t != null && t.contains("no va a poder tomar tu pedido"))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  /**
   * El caso de #255/#256/#260: el que rechaza era el único. No se promete una
   * búsqueda que no puede pasar, y el equipo se entera de que hay demanda sin
   * oferta — que es la acción real (captar o aprobar a quien está pendiente).
   */
  @Test
  void siNoQuedaNadieSeDiceLaVerdadYSeAvisaAlEquipo() throws Exception {
    String zone = "Solymar Sin Oferta Uno";
    Lead lead = makePasteleriaLead(zone);
    Provider unica = createPastelera("Pastelera Unica", zone);
    scheduler().processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus())
        .isEqualTo(LeadStatus.PROVIDER_CONTACTED);

    declineAsProvider(unica, lead.getId());

    String message = lastAgentMessage(lead.getId());
    assertThat(message).contains(SIN_NADIE_MAS);
    assertThat(message).contains("pasteleria");
    assertThat(message).contains(zone);
    // La promesa vacía es justamente lo que no puede volver a salir.
    assertThat(message).doesNotContain(PROMESA_DE_BUSQUEDA);

    verify(telegramNotifyService).notifyDemandWithoutSupply(any());

    // El pedido sigue vivo y sin dueño: si mañana se registra alguien, el
    // reintento de huérfanos lo encuentra.
    Lead released = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(released.getStatus()).isEqualTo(LeadStatus.NEW);
    assertThat(released.getAssignedProviderId()).isNull();
    assertThat(released.isReadyForMatching()).isTrue();
  }

  /** Si SÍ queda alguien, la promesa es verdadera: no se toca ni se avisa. */
  @Test
  void siQuedaOtroProveedorSeMantieneLaPromesaDeBusqueda() throws Exception {
    String zone = "Solymar Sin Oferta Dos";
    Lead lead = makePasteleriaLead(zone);
    Provider primera = createPastelera("Pastelera Primera", zone);
    createPastelera("Pastelera Segunda", zone);
    scheduler().processOnce();

    declineAsProvider(primera, lead.getId());

    String message = lastAgentMessage(lead.getId());
    assertThat(message).contains(PROMESA_DE_BUSQUEDA);
    assertThat(message).doesNotContain(SIN_NADIE_MAS);
    verify(telegramNotifyService, never()).notifyDemandWithoutSupply(any());
  }

  /**
   * El rechazo del segundo, cuando ya no queda un tercero, también avisa: la
   * demanda se queda sin oferta recién ahí, no en el primer rechazo.
   */
  @Test
  void elUltimoRechazoDeLaCadenaEsElQueAvisa() throws Exception {
    String zone = "Solymar Sin Oferta Tres";
    Lead lead = makePasteleriaLead(zone);
    Provider primera = createPastelera("Pastelera Cadena Uno", zone);
    Provider segunda = createPastelera("Pastelera Cadena Dos", zone);

    scheduler().processOnce();
    Long first = leadRepository.findById(lead.getId()).orElseThrow().getAssignedProviderId();
    Provider contactadaPrimero = first.equals(primera.getId()) ? primera : segunda;
    Provider contactadaDespues = first.equals(primera.getId()) ? segunda : primera;

    declineAsProvider(contactadaPrimero, lead.getId());
    verify(telegramNotifyService, never()).notifyDemandWithoutSupply(any());

    // El reintento se lo ofrece a la otra, que también rechaza.
    scheduler().processOnce();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getAssignedProviderId())
        .isEqualTo(contactadaDespues.getId());
    declineAsProvider(contactadaDespues, lead.getId());

    assertThat(lastAgentMessage(lead.getId())).contains(SIN_NADIE_MAS);
    verify(telegramNotifyService).notifyDemandWithoutSupply(any());
  }
}
