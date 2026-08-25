package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Panel self-service del comercio (Fase 5): {@code GET
 * /api/public/merchant/{token}} + {@code renew}/{@code pause}. Cada test usa
 * su propio comercio con {@code panelToken} propio (mismo criterio de
 * aislamiento que el resto de la suite: H2 compartida entre contextos).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MerchantPanelSurfaceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private OfferRepository offerRepository;

  private Business persistBusiness(String tag) {
    Business business = new Business();
    business.setName("Comercio Panel Test " + tag);
    business.setWhatsappNumber("0988" + tag);
    business.setCategory("otro");
    business.setPrimaryZone("Solymar");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setPanelToken("panel-surface-token-" + tag);
    return businessRepository.save(business);
  }

  private Offer persistOffer(
      Business business, OfferStatus status, OffsetDateTime validFrom, OffsetDateTime validUntil, String title
  ) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle(title);
    offer.setCategory("otro");
    offer.setStatus(status);
    offer.setValidFrom(validFrom);
    offer.setValidUntil(validUntil);
    offer.setViewCount(3);
    return offerRepository.save(offer);
  }

  // ---- GET /api/public/merchant/{token} ----

  @Test
  void tokenInexistenteEs404Opaco() throws Exception {
    mockMvc.perform(get("/api/public/merchant/{token}", "no-existe-este-token"))
        .andExpect(status().isNotFound());
  }

  @Test
  void panelDevuelveTodasLasOfertasOrdenadasYConMetricasReales() throws Exception {
    Business business = persistBusiness("001");
    OffsetDateTime now = OffsetDateTime.now();
    persistOffer(business, OfferStatus.ACTIVE, now.minusDays(1), now.plusDays(10), "Activa lejos");
    persistOffer(business, OfferStatus.ACTIVE, now.minusDays(1), now.plusDays(3), "Activa cerca");
    persistOffer(business, OfferStatus.DRAFT, null, null, "Borrador");
    persistOffer(business, OfferStatus.EXPIRED, now.minusDays(20), now.minusDays(5), "Vencida");
    persistOffer(business, OfferStatus.REJECTED, null, null, "Rechazada");

    MvcResult result = mockMvc.perform(get("/api/public/merchant/{token}", business.getPanelToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.business.id").value(business.getId()))
        .andExpect(jsonPath("$.business.name").value(business.getName()))
        .andExpect(jsonPath("$.business.category").value("otro"))
        .andExpect(jsonPath("$.business.primaryZone").value("Solymar"))
        .andReturn();

    String json = result.getResponse().getContentAsString();
    List<String> titles = JsonPath.read(json, "$.offers[*].title");
    // ACTIVE primero (validUntil desc entre ellas), luego DRAFT, EXPIRED, REJECTED.
    assertThat(titles).containsExactly("Activa lejos", "Activa cerca", "Borrador", "Vencida", "Rechazada");

    // Métricas reales del dueño: viewCount=3 (por debajo del umbral público de 10) SIGUE visible acá.
    Number viewCount = JsonPath.read(json, "$.offers[0].viewCount");
    assertThat(viewCount.intValue()).isEqualTo(3);
  }

  @Test
  void panelIncluyeInquiryCountYLeadCountAunqueSeanCero() throws Exception {
    Business business = persistBusiness("002");
    persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(5), "Oferta sola");

    mockMvc.perform(get("/api/public/merchant/{token}", business.getPanelToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.offers[0].inquiryCount").value(0))
        .andExpect(jsonPath("$.offers[0].leadCount").value(0));
  }

  // ---- renew ----

  @Test
  void renewActivaExtiendeDesdeValidUntilActual() throws Exception {
    Business business = persistBusiness("003");
    OffsetDateTime now = OffsetDateTime.now();
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, now.minusDays(1), now.plusDays(2), "Activa a renovar");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/renew", business.getPanelToken(), offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"weeks\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    Offer reloaded = offerRepository.findById(offer.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(OfferStatus.ACTIVE);
    assertThat(reloaded.getValidUntil()).isCloseTo(now.plusDays(2).plusDays(14), within(10, ChronoUnit.SECONDS));
  }

  @Test
  void renewActivaVencidaEnLaPracticaExtiendeDesdeAhoraNoDesdeElPasado() throws Exception {
    Business business = persistBusiness("004");
    OffsetDateTime now = OffsetDateTime.now();
    // ACTIVE con validUntil ya pasado (scheduler todavía no corrió) — extender debe partir de "ahora", no del pasado.
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, now.minusDays(20), now.minusHours(1), "Activa vencida en la práctica");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/renew", business.getPanelToken(), offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"weeks\":1}"))
        .andExpect(status().isOk());

    Offer reloaded = offerRepository.findById(offer.getId()).orElseThrow();
    assertThat(reloaded.getValidUntil()).isCloseTo(now.plusDays(7), within(10, ChronoUnit.SECONDS));
  }

  @Test
  void renewExpiradaVuelveADraftConVigenciaNuevaDesdeAhora() throws Exception {
    Business business = persistBusiness("005");
    OffsetDateTime now = OffsetDateTime.now();
    Offer offer = persistOffer(business, OfferStatus.EXPIRED, now.minusDays(20), now.minusDays(5), "Vencida a renovar");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/renew", business.getPanelToken(), offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"weeks\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"));

    Offer reloaded = offerRepository.findById(offer.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(OfferStatus.DRAFT);
    assertThat(reloaded.getValidFrom()).isCloseTo(now, within(10, ChronoUnit.SECONDS));
    assertThat(reloaded.getValidUntil()).isCloseTo(now.plusDays(28), within(10, ChronoUnit.SECONDS));
  }

  @Test
  void renewDraftDevuelve409() throws Exception {
    Business business = persistBusiness("006");
    Offer offer = persistOffer(business, OfferStatus.DRAFT, null, null, "Borrador no renovable");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/renew", business.getPanelToken(), offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"weeks\":1}"))
        .andExpect(status().isConflict());
  }

  @Test
  void renewRejectedDevuelve409() throws Exception {
    Business business = persistBusiness("007");
    Offer offer = persistOffer(business, OfferStatus.REJECTED, null, null, "Rechazada no renovable");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/renew", business.getPanelToken(), offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"weeks\":1}"))
        .andExpect(status().isConflict());
  }

  @Test
  void renewConWeeksInvalidoDevuelve400() throws Exception {
    Business business = persistBusiness("008");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(5), "Activa");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/renew", business.getPanelToken(), offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"weeks\":3}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void renewSinWeeksDevuelve400() throws Exception {
    Business business = persistBusiness("009");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(5), "Activa");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/renew", business.getPanelToken(), offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
  }

  // ---- pause ----

  @Test
  void pauseActivaLaExpiraYa() throws Exception {
    Business business = persistBusiness("010");
    OffsetDateTime now = OffsetDateTime.now();
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, now.minusDays(1), now.plusDays(10), "Activa a pausar");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/pause", business.getPanelToken(), offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));

    Offer reloaded = offerRepository.findById(offer.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(OfferStatus.EXPIRED);
    assertThat(reloaded.getValidUntil()).isCloseTo(now, within(10, ChronoUnit.SECONDS));
  }

  @Test
  void pauseNoActivaDevuelve409() throws Exception {
    Business business = persistBusiness("011");
    Offer offer = persistOffer(business, OfferStatus.DRAFT, null, null, "Borrador no pausable");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/pause", business.getPanelToken(), offer.getId()))
        .andExpect(status().isConflict());
  }

  // ---- aislamiento entre comercios ----

  @Test
  void ofertaDeOtroComercioEs404OpacoEnRenew() throws Exception {
    Business businessA = persistBusiness("012a");
    Business businessB = persistBusiness("012b");
    Offer offerOfB = persistOffer(businessB, OfferStatus.ACTIVE, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(5), "Oferta de B");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/renew", businessA.getPanelToken(), offerOfB.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"weeks\":1}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void ofertaDeOtroComercioEs404OpacoEnPause() throws Exception {
    Business businessA = persistBusiness("013a");
    Business businessB = persistBusiness("013b");
    Offer offerOfB = persistOffer(businessB, OfferStatus.ACTIVE, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(5), "Oferta de B pausable");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/pause", businessA.getPanelToken(), offerOfB.getId()))
        .andExpect(status().isNotFound());
  }

  @Test
  void offerIdInexistenteEs404Opaco() throws Exception {
    Business business = persistBusiness("014");

    mockMvc.perform(post("/api/public/merchant/{token}/offers/{id}/pause", business.getPanelToken(), 999999999L))
        .andExpect(status().isNotFound());
  }
}
