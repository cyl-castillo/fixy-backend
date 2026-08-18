package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferInquiry;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferInquiryRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.OffsetDateTime;
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
 * Ruta comercio del CTA de ofertas (FIXY_OFERTAS_CTA_DESIGN.md §4): mini-form
 * público de consulta ({@code POST /api/public/offers/{id}/inquiries}) +
 * drill-down admin ({@code GET}/{@code PATCH /api/offers/{id}/inquiries}).
 * Cada aserción filtra por los ids/nombres creados en ESTE test (H2
 * compartida entre contextos, no asumir tabla vacía).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OfferInquiryTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private OfferRepository offerRepository;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private OfferInquiryRepository offerInquiryRepository;

  private Business persistBusiness(String name, String whatsapp, Long providerId) {
    Business business = new Business();
    business.setName(name);
    business.setWhatsappNumber(whatsapp);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setProviderId(providerId);
    return businessRepository.save(business);
  }

  private Offer persistOffer(Business business, OfferStatus status, OffsetDateTime validUntil) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle("Oferta inquiry test " + business.getId());
    offer.setCategory("otro");
    offer.setZone("Solymar");
    offer.setDiscountText("20% off");
    offer.setStatus(status);
    offer.setValidUntil(validUntil);
    return offerRepository.save(offer);
  }

  private String body(String name, String whatsapp, String message, String website) {
    return """
        {"name": %s, "whatsappNumber": %s, "message": %s, "website": %s}
        """.formatted(json(name), json(whatsapp), json(message), json(website));
  }

  private String json(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  @Test
  void creaLaConsultaCuandoLaOfertaEsComercioYResponde201SinExponerElId() throws Exception {
    Business business = persistBusiness("Comercio Inquiry OK Test", "098555001", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Test", "099111222", "¿Tienen talle M?", null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ok").value(true))
        .andExpect(jsonPath("$.id").doesNotExist());

    List<OfferInquiry> saved = offerInquiryRepository.findByOfferIdOrderByCreatedAtDesc(offer.getId());
    assertThat(saved).hasSize(1);
    assertThat(saved.get(0).getStatus()).isEqualTo(OfferInquiry.STATUS_NEW);
    assertThat(saved.get(0).getBusinessId()).isEqualTo(business.getId());
    assertThat(saved.get(0).getName()).isEqualTo("Vecina Test");
  }

  @Test
  void devuelve409SiLaOfertaEsRutaProvider() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Provider Test", "098555002", 42L);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Test", "099111222", "Consulta que no debería aceptarse", null)))
        .andExpect(status().isConflict());

    assertThat(offerInquiryRepository.findByOfferIdOrderByCreatedAtDesc(offer.getId())).isEmpty();
  }

  @Test
  void devuelve409SiLaOfertaEsScrapeadaSinCtaDeContacto() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Scraped Test", "scraped:comercio-inquiry-scraped-test", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Test", "099111222", "Consulta que no debería aceptarse", null)))
        .andExpect(status().isConflict());

    assertThat(offerInquiryRepository.findByOfferIdOrderByCreatedAtDesc(offer.getId())).isEmpty();
  }

  @Test
  void devuelve404SiLaOfertaNoExiste() throws Exception {
    mockMvc.perform(post("/api/public/offers/{id}/inquiries", 999999)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Test", "099111222", "Consulta a oferta inexistente", null)))
        .andExpect(status().isNotFound());
  }

  @Test
  void devuelve404SiLaOfertaEstaEnDraft() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Draft Test", "098555003", null);
    Offer offer = persistOffer(business, OfferStatus.DRAFT, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Test", "099111222", "Consulta a oferta en draft", null)))
        .andExpect(status().isNotFound());
  }

  @Test
  void honeypotNoPersisteNiRompeYResponde201Igual() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Honeypot Test", "098555004", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Bot Test", "000000000", "x", "http://bot.example")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ok").value(true));

    assertThat(offerInquiryRepository.findByOfferIdOrderByCreatedAtDesc(offer.getId())).isEmpty();
  }

  @Test
  void honeypotIgnoraCualquierOtroErrorDeValidacionYNoRomperElRequest() throws Exception {
    // Mensaje vacío (violaría el mínimo de 5 caracteres) + website lleno:
    // el honeypot se chequea PRIMERO, nunca debe llegar a 400.
    Business business = persistBusiness("Comercio Inquiry Honeypot Invalido Test", "098555005", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("", "", "", "relleno-bot")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ok").value(true));
  }

  @Test
  void mensajeMuyCortoEsRechazado() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Corto Test", "098555006", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Test", "099111222", "hi", null)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mensajeMuyLargoEsRechazado() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Largo Test", "098555007", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    String tooLong = "a".repeat(501);
    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Test", "099111222", tooLong, null)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void nombreOWhatsappVacioEsRechazado() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Sin Nombre Test", "098555008", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(null, "099111222", "Consulta válida de largo suficiente", null)))
        .andExpect(status().isBadRequest());

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Test", null, "Consulta válida de largo suficiente", null)))
        .andExpect(status().isBadRequest());
  }

  // --- admin: GET/PATCH /api/offers/{id}/inquiries ---

  @Test
  void adminListaLasConsultasDeUnaOfertaMasNuevasPrimero() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Admin List Test", "098555009", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Primera Consulta", "099111001", "Primera consulta de largo suficiente", null)))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Segunda Consulta", "099111002", "Segunda consulta de largo suficiente", null)))
        .andExpect(status().isCreated());

    MvcResult res = mockMvc.perform(get("/api/offers/{id}/inquiries", offer.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    List<String> names = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$[*].name");
    assertThat(names).containsExactly("Segunda Consulta", "Primera Consulta");
  }

  @Test
  void adminListaRequiereAutenticacionOps() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Admin Auth Test", "098555010", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/offers/{id}/inquiries", offer.getId()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminMarcaForwardedYQuedaPersistido() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Forward Test", "098555011", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));
    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Forward Test", "099111003", "Consulta a marcar forwarded", null)))
        .andExpect(status().isCreated());
    Long inquiryId = offerInquiryRepository.findByOfferIdOrderByCreatedAtDesc(offer.getId()).get(0).getId();

    mockMvc.perform(patch("/api/offers/{id}/inquiries/{inquiryId}", offer.getId(), inquiryId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"FORWARDED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FORWARDED"));

    assertThat(offerInquiryRepository.findById(inquiryId).orElseThrow().getStatus())
        .isEqualTo(OfferInquiry.STATUS_FORWARDED);
  }

  @Test
  void adminRechazaUnStatusNoSoportado() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Status Invalido Test", "098555012", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));
    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Status Invalido Test", "099111004", "Consulta con status inválido después", null)))
        .andExpect(status().isCreated());
    Long inquiryId = offerInquiryRepository.findByOfferIdOrderByCreatedAtDesc(offer.getId()).get(0).getId();

    mockMvc.perform(patch("/api/offers/{id}/inquiries/{inquiryId}", offer.getId(), inquiryId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"CLOSED\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void adminDevuelve404SiLaInquiryNoPerteneceALaOferta() throws Exception {
    Business business1 = persistBusiness("Comercio Inquiry Cross A Test", "098555013", null);
    Offer offer1 = persistOffer(business1, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));
    Business business2 = persistBusiness("Comercio Inquiry Cross B Test", "098555014", null);
    Offer offer2 = persistOffer(business2, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer1.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Cross Test", "099111005", "Consulta de la oferta uno nomás", null)))
        .andExpect(status().isCreated());
    Long inquiryId = offerInquiryRepository.findByOfferIdOrderByCreatedAtDesc(offer1.getId()).get(0).getId();

    mockMvc.perform(patch("/api/offers/{id}/inquiries/{inquiryId}", offer2.getId(), inquiryId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"FORWARDED\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void inquiryCountEnOfferResponseAdminReflejaLasConsultasDeLaOferta() throws Exception {
    Business business = persistBusiness("Comercio Inquiry Count Test", "098555015", null);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/offers/{id}", offer.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inquiryCount").value(0));

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Vecina Count Test", "099111006", "Consulta que suma al contador admin", null)))
        .andExpect(status().isCreated());

    mockMvc.perform(get("/api/offers/{id}", offer.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inquiryCount").value(1));
  }
}
