package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderOffer;
import com.fixy.backend.model.ProviderOfferContext;
import com.fixy.backend.model.ProviderOfferResponse;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderOfferRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadAgentService;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.MatchingWatchdogScheduler;
import com.fixy.backend.service.ProviderCatalogService;
import com.fixy.backend.service.ProviderSelfService;
import com.fixy.backend.service.PushNotificationService;
import com.fixy.backend.service.SearchDeadlineService;
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
 * Tier 2 (contrato §A.1/§A.2): registro de ofertas uno-a-uno —
 * {@code contactTopMatch} escribe la oferta abierta, y se cierra
 * ACCEPTED/DECLINED/TIMEOUT según qué camino la resuelve. El pozo abierto
 * (aceptar un lead nunca ofrecido a este proveedor) no crea ni cierra
 * ninguna oferta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fixy.payments.provider-commission-enabled=false")
class ProviderOffersFlowTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ProviderLeadDeclineRepository declineRepository;
  @Autowired private ProviderOfferRepository providerOfferRepository;
  @Autowired private LeadAgentService leadAgentService;
  @Autowired private ProviderCatalogService providerCatalogService;
  @Autowired private ProviderSelfService providerSelfService;
  @Autowired private LeadTimelineService timelineService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private PushNotificationService pushNotificationService;
  @Autowired private TelegramNotifyService telegramNotifyService;
  @Autowired private SearchDeadlineService searchDeadlineService;

  private Lead createChatLead() throws Exception {
    MvcResult res = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer id = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    return leadRepository.findById(Long.valueOf(id)).orElseThrow();
  }

  private Lead readyLead(String zone) throws Exception {
    Lead lead = createChatLead();
    lead.setDetectedCategory("plomeria");
    lead.setLocation(zone);
    lead.setReadyForMatching(true);
    return leadRepository.save(lead);
  }

  private Provider createProvider(String name, String zone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("0997" + Math.abs(name.hashCode() % 1000000));
    provider.setCategories("plomeria");
    provider.setPrimaryZone(zone);
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setAccessToken("token-" + name.replace(' ', '-'));
    return providerRepository.save(provider);
  }

  private List<ProviderOffer> offersFor(Lead lead, Provider provider) {
    return providerOfferRepository.findByLeadIdAndProviderIdOrderByOfferedAtDesc(lead.getId(), provider.getId());
  }

  @Test
  void contactTopMatchRegistraUnaOfertaAbiertaConContextoInitial() throws Exception {
    String zone = "Zona Oferta Initial";
    Provider provider = createProvider("Plomero Oferta Initial", zone);
    Lead lead = readyLead(zone);

    boolean contacted = leadAgentService.matchNow(lead);
    assertThat(contacted).isTrue();

    List<ProviderOffer> offers = offersFor(lead, provider);
    assertThat(offers).hasSize(1);
    ProviderOffer offer = offers.get(0);
    assertThat(offer.getContext()).isEqualTo(ProviderOfferContext.INITIAL);
    assertThat(offer.getResponse()).isNull();
    assertThat(offer.getRespondedAt()).isNull();
    // Sin availabilityWindows declarada por el proveedor = siempre disponible.
    assertThat(offer.isInWindow()).isTrue();
  }

  @Test
  void aceptarDesdeElPanelCierraLaOfertaComoAccepted() throws Exception {
    String zone = "Zona Oferta Accept";
    Provider provider = createProvider("Plomero Oferta Accept", zone);
    Lead lead = readyLead(zone);
    leadAgentService.matchNow(lead);
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus())
        .isEqualTo(LeadStatus.PROVIDER_CONTACTED);

    mockMvc.perform(post("/api/public/providers/{pid}/leads/{lid}/status", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"ASSIGNED\"}"))
        .andExpect(status().isOk());

    List<ProviderOffer> offers = offersFor(lead, provider);
    assertThat(offers).hasSize(1);
    assertThat(offers.get(0).getResponse()).isEqualTo(ProviderOfferResponse.ACCEPTED);
    assertThat(offers.get(0).getRespondedAt()).isNotNull();
  }

  @Test
  void declinarDesdeLaBandejaCierraLaOfertaComoDeclined() throws Exception {
    String zone = "Zona Oferta Decline";
    Provider provider = createProvider("Plomero Oferta Decline", zone);
    Lead lead = readyLead(zone);
    leadAgentService.matchNow(lead);

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/decline", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken()))
        .andExpect(status().isNoContent());

    List<ProviderOffer> offers = offersFor(lead, provider);
    assertThat(offers).hasSize(1);
    assertThat(offers.get(0).getResponse()).isEqualTo(ProviderOfferResponse.DECLINED);
  }

  @Test
  void watchdogCierraLaOfertaComoTimeoutAlLiberarSinAlternativa() throws Exception {
    String zone = "Zona Oferta Timeout";
    Provider provider = createProvider("Plomero Oferta Timeout", zone);
    Lead lead = readyLead(zone);
    leadAgentService.matchNow(lead);

    MatchingWatchdogScheduler scheduler = new MatchingWatchdogScheduler(
        leadRepository, leadEventRepository, leadMessageRepository, providerRepository, declineRepository,
        providerOfferRepository, providerCatalogService, providerSelfService, leadAgentService, timelineService,
        leadMessageService, pushNotificationService, telegramNotifyService, searchDeadlineService,
        true, 45, 20, 12, 4, 14, 60,
        Clock.fixed(Instant.now().plus(Duration.ofHours(13)), ZoneOffset.UTC));
    scheduler.processOnce();

    List<ProviderOffer> offers = offersFor(lead, provider);
    assertThat(offers).hasSize(1);
    assertThat(offers.get(0).getResponse()).isEqualTo(ProviderOfferResponse.TIMEOUT);
    assertThat(offers.get(0).getRespondedAt()).isNotNull();
  }

  @Test
  void aceptarDesdeElPozoAbiertoNoCreaNiCierraNingunaOferta() throws Exception {
    String zone = "Zona Oferta Pozo";
    Provider provider = createProvider("Plomero Oferta Pozo", zone);
    // Lead listo pero NUNCA contactado (status NEW): lo toma directo del
    // pozo, sin que exista ninguna oferta previa para el par.
    Lead lead = readyLead(zone);

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/accept", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken()))
        .andExpect(status().isOk());

    assertThat(offersFor(lead, provider)).isEmpty();
  }
}
