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
import com.fixy.backend.model.ProviderVerificationStatus;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderOfferRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bug de prod (2026-10-27): las 8 filas de {@code provider_offers} tenían
 * response NULL aunque hubo aceptaciones reales por WhatsApp (leads 277 y
 * 280) y {@code providers.last_responded_at} no se movía. Solo el cambio de
 * estado desde el panel cerraba la oferta; el SÍ/NO por WhatsApp y la
 * bandeja de oportunidades no. Estos tests fijan que TODOS los caminos de
 * respuesta cierran la oferta abierta y marcan last_responded_at.
 *
 * Sin @Transactional a propósito: el UPDATE puntual de last_responded_at y
 * el cierre de la oferta tienen que verse desde otra transacción (puntos
 * ciegos de MockMvc).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.provider-commission-enabled=false",
    "fixy.matching.watchdog.enabled=false"
})
class WhatsAppOfferResponseRecordingTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadRepository leadRepository;
  @Autowired private ProviderOfferRepository providerOfferRepository;

  private Provider persistProvider(String name, String phone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone(phone);
    provider.setWhatsappNumber(phone);
    provider.setCategories("decoracion_fiestas");
    provider.setPrimaryZone("Lagomar");
    provider.setCoverageZones("Lagomar");
    provider.setCity("Ciudad de la Costa");
    provider.setDepartment("Canelones");
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setVerificationStatus(ProviderVerificationStatus.BASIC_CHECKED);
    provider.setAccessToken("tok-" + phone);
    return providerRepository.save(provider);
  }

  private Lead persistLeadOfferedTo(Provider provider) {
    Lead lead = new Lead();
    lead.setName("Vecino de prueba");
    lead.setPhone("099444" + provider.getId());
    lead.setProblem("Decoración para un cumpleaños");
    lead.setChannel("web-chat");
    lead.setDetectedCategory("decoracion_fiestas");
    lead.setLocation("Lagomar");
    lead.setUrgency("media");
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    lead.setAccessToken("tok-lead-offer-" + provider.getId());
    lead = leadRepository.save(lead);

    ProviderOffer offer = new ProviderOffer();
    offer.setLeadId(lead.getId());
    offer.setProviderId(provider.getId());
    offer.setContext(ProviderOfferContext.INITIAL);
    offer.setOfferedAt(OffsetDateTime.now().minusMinutes(2));
    providerOfferRepository.save(offer);
    return lead;
  }

  private void whatsappText(String from, String id, String text) throws Exception {
    String payload = """
        {"entry":[{"changes":[{"value":{"messages":[{"from":"%s","id":"%s","type":"text","text":{"body":"%s"}}]}}]}]}
        """.formatted(from, id, text);
    mockMvc.perform(post("/api/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(payload))
        .andExpect(status().isOk());
  }

  private ProviderOffer onlyOffer(Lead lead, Provider provider) {
    List<ProviderOffer> offers =
        providerOfferRepository.findByLeadIdAndProviderIdOrderByOfferedAtDesc(lead.getId(), provider.getId());
    assertThat(offers).hasSize(1);
    return offers.get(0);
  }

  @Test
  void siPorWhatsAppCierraLaOfertaComoAcceptedYMarcaLastRespondedAt() throws Exception {
    Provider provider = persistProvider("Deco Acepta WA", "099810001");
    Lead lead = persistLeadOfferedTo(provider);
    assertThat(providerRepository.findById(provider.getId()).orElseThrow().getLastRespondedAt()).isNull();

    whatsappText(provider.getPhone(), "wamid.rec.accept", "SI");

    ProviderOffer offer = onlyOffer(lead, provider);
    assertThat(offer.getResponse()).isEqualTo(ProviderOfferResponse.ACCEPTED);
    assertThat(offer.getRespondedAt()).isNotNull();
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.ASSIGNED);
    assertThat(providerRepository.findById(provider.getId()).orElseThrow().getLastRespondedAt()).isNotNull();
  }

  @Test
  void noPorWhatsAppCierraLaOfertaComoDeclinedYMarcaLastRespondedAt() throws Exception {
    Provider provider = persistProvider("Deco Rechaza WA", "099810002");
    Lead lead = persistLeadOfferedTo(provider);

    whatsappText(provider.getPhone(), "wamid.rec.decline", "NO puedo");

    ProviderOffer offer = onlyOffer(lead, provider);
    assertThat(offer.getResponse()).isEqualTo(ProviderOfferResponse.DECLINED);
    assertThat(offer.getRespondedAt()).isNotNull();
    assertThat(providerRepository.findById(provider.getId()).orElseThrow().getLastRespondedAt()).isNotNull();
  }

  @Test
  void aceptarDesdeLaBandejaCierraLaOfertaComoAcceptedYMarcaLastRespondedAt() throws Exception {
    Provider provider = persistProvider("Deco Acepta Bandeja", "099810003");
    Lead lead = persistLeadOfferedTo(provider);

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/accept", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken()))
        .andExpect(status().isOk());

    ProviderOffer offer = onlyOffer(lead, provider);
    assertThat(offer.getResponse()).isEqualTo(ProviderOfferResponse.ACCEPTED);
    assertThat(providerRepository.findById(provider.getId()).orElseThrow().getLastRespondedAt()).isNotNull();
  }

  @Test
  void rechazarDesdeLaBandejaMarcaLastRespondedAt() throws Exception {
    Provider provider = persistProvider("Deco Rechaza Bandeja", "099810004");
    Lead lead = persistLeadOfferedTo(provider);

    mockMvc.perform(post("/api/public/providers/{pid}/opportunities/{lid}/decline", provider.getId(), lead.getId())
            .param("token", provider.getAccessToken()))
        .andExpect(status().isNoContent());

    assertThat(onlyOffer(lead, provider).getResponse()).isEqualTo(ProviderOfferResponse.DECLINED);
    assertThat(providerRepository.findById(provider.getId()).orElseThrow().getLastRespondedAt()).isNotNull();
  }

  @Test
  void aceptarUnLeadSinOfertaPrevia_pozoAbierto_noFallaYIgualMarcaLastRespondedAt() throws Exception {
    Provider provider = persistProvider("Deco Pozo Abierto", "099810005");
    Lead lead = persistLeadOfferedTo(provider);
    providerOfferRepository.deleteAll(
        providerOfferRepository.findByLeadIdAndProviderIdOrderByOfferedAtDesc(lead.getId(), provider.getId()));

    whatsappText(provider.getPhone(), "wamid.rec.pozo", "dale");

    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getStatus()).isEqualTo(LeadStatus.ASSIGNED);
    assertThat(providerOfferRepository
        .findByLeadIdAndProviderIdOrderByOfferedAtDesc(lead.getId(), provider.getId())).isEmpty();
    assertThat(providerRepository.findById(provider.getId()).orElseThrow().getLastRespondedAt()).isNotNull();
  }
}
