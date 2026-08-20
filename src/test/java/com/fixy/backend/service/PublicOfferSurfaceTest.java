package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Primera superficie de cliente de Ofertas (roadmap Historia 3.3 / Loop 2 —
 * SOLO la lectura pública y los contadores, NADA de tab). Cada aserción
 * filtra por los ids/nombres creados en ESTE test (lección conocida del
 * repo: H2 compartida entre contextos, no asumir tabla vacía).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicOfferSurfaceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private OfferRepository offerRepository;
  @Autowired private BusinessRepository businessRepository;

  private Business persistBusiness(String name, String whatsapp) {
    Business business = new Business();
    business.setName(name);
    business.setWhatsappNumber(whatsapp);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    return businessRepository.save(business);
  }

  private Offer persistOffer(Business business, OfferStatus status, String category, String zone,
      OffsetDateTime validUntil) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle("Oferta pública test " + business.getId());
    offer.setCategory(category);
    offer.setZone(zone);
    offer.setDiscountText("20% off");
    offer.setSourceMessageRaw("texto interno que NUNCA debe llegar al cliente");
    offer.setStatus(status);
    offer.setValidUntil(validUntil);
    return offerRepository.save(offer);
  }

  // --- ctaType: ruteo determinista (FIXY_OFERTAS_CTA_DESIGN.md §2) ---

  @Test
  void ctaTypeEsProviderCuandoElBusinessTieneProviderIdSeteado() throws Exception {
    Business business = persistBusiness("Comercio Cta Provider Test", "098444020");
    business.setProviderId(999L);
    businessRepository.save(business);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ctaType").value("provider"));
  }

  @Test
  void ctaTypeEsComercioCuandoElBusinessTieneWhatsappRealYSinProviderId() throws Exception {
    Business business = persistBusiness("Comercio Cta Comercio Test", "098444021");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ctaType").value("comercio"));
  }

  @Test
  void ctaTypeEsNoneCuandoElWhatsappEsSintetico() throws Exception {
    Business business = persistBusiness("Comercio Cta Scraped Test", "scraped:comercio-cta-scraped-test");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ctaType").value("none"));
  }

  @Test
  void ctaTypeEsProviderAunqueElWhatsappSeaSinteticoSiTieneProviderId() throws Exception {
    // Caso borde documentado en el diseño §2: providerId es la fuente de
    // verdad más fuerte, se evalúa primero — un comercio scrapeado que
    // además tiene providerId (no debería pasar en la práctica, pero el
    // orden de evaluación lo cubre igual) cae en PROVIDER, no en NONE.
    Business business = persistBusiness("Comercio Cta Borde Test", "scraped:comercio-cta-borde-test");
    business.setProviderId(1234L);
    businessRepository.save(business);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ctaType").value("provider"));
  }

  @Test
  void soloTraeActivasYVigentes() throws Exception {
    Business business = persistBusiness("Panadería Pública Test", "098444001");
    Offer active = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));
    Offer draft = persistOffer(business, OfferStatus.DRAFT, "otro", "Solymar", OffsetDateTime.now().plusDays(5));
    Offer rejected = persistOffer(business, OfferStatus.REJECTED, "otro", "Solymar", OffsetDateTime.now().plusDays(5));
    Offer expired = persistOffer(business, OfferStatus.EXPIRED, "otro", "Solymar", OffsetDateTime.now().plusDays(5));
    Offer activeButPastValidUntil =
        persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().minusHours(1));

    MvcResult res = mockMvc.perform(get("/api/public/offers"))
        .andExpect(status().isOk())
        .andReturn();

    List<Integer> ids = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$[*].id");
    assertThat(ids).contains(active.getId().intValue());
    assertThat(ids).doesNotContain(draft.getId().intValue(), rejected.getId().intValue(),
        expired.getId().intValue(), activeButPastValidUntil.getId().intValue());
  }

  @Test
  void elDtoPublicoNuncaExponeDatosInternosNiDeContacto() throws Exception {
    Business business = persistBusiness("Comercio Contacto Test", "098444002");
    persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    MvcResult res = mockMvc.perform(get("/api/public/offers").param("zone", "Solymar"))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).doesNotContain("sourceMessageRaw");
    assertThat(body).doesNotContain("whatsappNumber");
    assertThat(body).doesNotContain("098444002");
    assertThat(body).contains("Comercio Contacto Test");
  }

  @Test
  void filtraPorZonaConLaJerarquiaDeCoverageZone() throws Exception {
    Business business = persistBusiness("Comercio Zona Test", "098444003");
    Offer enSolymar = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));
    Offer sinZona = persistOffer(business, OfferStatus.ACTIVE, "otro", null, OffsetDateTime.now().plusDays(5));

    // El paraguas "Ciudad de la Costa" alcanza a una oferta declarada en Solymar.
    MvcResult res = mockMvc.perform(get("/api/public/offers").param("zone", "Ciudad de la Costa"))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> ids = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$[*].id");
    assertThat(ids).contains(enSolymar.getId().intValue());

    // Sin zona declarada: no aparece en un listado filtrado por zona (decisión documentada).
    assertThat(ids).doesNotContain(sinZona.getId().intValue());

    // Una zona ajena (El Pinar) no matchea una oferta de Solymar.
    MvcResult resAjena = mockMvc.perform(get("/api/public/offers").param("zone", "El Pinar"))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> idsAjena = com.jayway.jsonpath.JsonPath.read(resAjena.getResponse().getContentAsString(), "$[*].id");
    assertThat(idsAjena).doesNotContain(enSolymar.getId().intValue());
  }

  @Test
  void allZonesApareceEnCualquierFiltroDeZonaAunqueNoTengaZonaPropia() throws Exception {
    Business business = persistBusiness("Cadena All Zones Test", "098444008");
    Offer allZones = persistOffer(business, OfferStatus.ACTIVE, "otro", null, OffsetDateTime.now().plusDays(5));
    allZones.setAllZones(true);
    offerRepository.save(allZones);

    MvcResult resElPinar = mockMvc.perform(get("/api/public/offers").param("zone", "El Pinar"))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> idsElPinar = com.jayway.jsonpath.JsonPath.read(
        resElPinar.getResponse().getContentAsString(), "$[*].id");
    assertThat(idsElPinar).contains(allZones.getId().intValue());

    MvcResult resSolymar = mockMvc.perform(get("/api/public/offers").param("zone", "Solymar"))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> idsSolymar = com.jayway.jsonpath.JsonPath.read(
        resSolymar.getResponse().getContentAsString(), "$[*].id");
    assertThat(idsSolymar).contains(allZones.getId().intValue());
  }

  @Test
  void sinZonaYSinAllZonesSigueExcluidaDeUnFiltroDeZona() throws Exception {
    Business business = persistBusiness("Comercio Sin Zona Test", "098444009");
    Offer sinZona = persistOffer(business, OfferStatus.ACTIVE, "otro", null, OffsetDateTime.now().plusDays(5));
    // allZones queda false por default — decisión: distinto de "vale en toda zona".

    MvcResult res = mockMvc.perform(get("/api/public/offers").param("zone", "Solymar"))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> ids = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$[*].id");
    assertThat(ids).doesNotContain(sinZona.getId().intValue());
  }

  @Test
  void sourceNameSeExponeEnElDtoPublicoPeroSourceUrlYExternalKeyNo() throws Exception {
    Business business = persistBusiness("Comercio Fuente Test", "098444010");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));
    offer.setSourceName("Itaú beneficios");
    offer.setSourceUrl("https://itau.com.uy/beneficios/secreto-interno");
    offer.setExternalKey("itau-comercio-fuente-test-abc123");
    offerRepository.save(offer);

    MvcResult res = mockMvc.perform(get("/api/public/offers").param("zone", "Solymar"))
        .andExpect(status().isOk())
        .andReturn();
    String body = res.getResponse().getContentAsString();
    assertThat(body).contains("Itaú beneficios");
    assertThat(body).doesNotContain("itau.com.uy");
    assertThat(body).doesNotContain("itau-comercio-fuente-test-abc123");
  }

  @Test
  void getDevuelveLaOfertaSiEstaActivaYVigente() throws Exception {
    Business business = persistBusiness("Comercio Detalle Test", "098444011");
    Offer active = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", active.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(active.getId().intValue()))
        .andExpect(jsonPath("$.businessName").value("Comercio Detalle Test"))
        .andExpect(jsonPath("$.sourceMessageRaw").doesNotExist())
        .andExpect(jsonPath("$.whatsappNumber").doesNotExist());
  }

  @Test
  void getDevuelve404SiLaOfertaNoExiste() throws Exception {
    mockMvc.perform(get("/api/public/offers/{id}", 999999))
        .andExpect(status().isNotFound());
  }

  @Test
  void getDevuelve404SiLaOfertaEstaEnDraft() throws Exception {
    Business business = persistBusiness("Comercio Detalle Draft Test", "098444012");
    Offer draft = persistOffer(business, OfferStatus.DRAFT, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", draft.getId()))
        .andExpect(status().isNotFound());
  }

  @Test
  void getDevuelve404SiLaOfertaFueRechazada() throws Exception {
    Business business = persistBusiness("Comercio Detalle Rejected Test", "098444013");
    Offer rejected = persistOffer(business, OfferStatus.REJECTED, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", rejected.getId()))
        .andExpect(status().isNotFound());
  }

  @Test
  void getDevuelve404SiLaOfertaEstaVencidaAunqueSigaActive() throws Exception {
    Business business = persistBusiness("Comercio Detalle Vencida Test", "098444014");
    Offer vencida = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().minusHours(1));

    mockMvc.perform(get("/api/public/offers/{id}", vencida.getId()))
        .andExpect(status().isNotFound());
  }

  @Test
  void getDevuelve404SiLaOfertaEstaExpired() throws Exception {
    Business business = persistBusiness("Comercio Detalle Expired Test", "098444015");
    Offer expired = persistOffer(business, OfferStatus.EXPIRED, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", expired.getId()))
        .andExpect(status().isNotFound());
  }

  @Test
  void filtraPorCategoria() throws Exception {
    Business business = persistBusiness("Comercio Categoria Test", "098444004");
    Offer pasteleria = persistOffer(business, OfferStatus.ACTIVE, "pasteleria", "Solymar", OffsetDateTime.now().plusDays(5));
    Offer jardineria = persistOffer(business, OfferStatus.ACTIVE, "jardineria", "Solymar", OffsetDateTime.now().plusDays(5));

    MvcResult res = mockMvc.perform(get("/api/public/offers").param("category", "pasteleria"))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> ids = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$[*].id");
    assertThat(ids).contains(pasteleria.getId().intValue()).doesNotContain(jardineria.getId().intValue());
  }

  @Test
  void countDevuelveElNumeroDeOfertasVigentes() throws Exception {
    Business business = persistBusiness("Comercio Count Test", "098444005");
    persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));
    persistOffer(business, OfferStatus.DRAFT, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    MvcResult before = mockMvc.perform(get("/api/public/offers/count"))
        .andExpect(status().isOk())
        .andReturn();
    long countBefore = ((Number) com.jayway.jsonpath.JsonPath.read(
        before.getResponse().getContentAsString(), "$.count")).longValue();

    Business business2 = persistBusiness("Comercio Count Test 2", "098444006");
    persistOffer(business2, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    MvcResult after = mockMvc.perform(get("/api/public/offers/count"))
        .andExpect(status().isOk())
        .andReturn();
    long countAfter = ((Number) com.jayway.jsonpath.JsonPath.read(
        after.getResponse().getContentAsString(), "$.count")).longValue();

    assertThat(countAfter).isEqualTo(countBefore + 1);
  }

  @Test
  void viewClickYLikeSonPublicosYSumanElContadorVisibleEnElDtoAdmin() throws Exception {
    Business business = persistBusiness("Comercio Metrica Test", "098444007");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    // Sin httpBasic: son endpoints públicos.
    mockMvc.perform(post("/api/public/offers/{id}/view", offer.getId())).andExpect(status().isNoContent());
    mockMvc.perform(post("/api/public/offers/{id}/view", offer.getId())).andExpect(status().isNoContent());
    mockMvc.perform(post("/api/public/offers/{id}/click", offer.getId())).andExpect(status().isNoContent());
    mockMvc.perform(post("/api/public/offers/{id}/like", offer.getId())).andExpect(status().isNoContent());
    mockMvc.perform(post("/api/public/offers/{id}/like", offer.getId())).andExpect(status().isNoContent());
    mockMvc.perform(post("/api/public/offers/{id}/like", offer.getId())).andExpect(status().isNoContent());

    mockMvc.perform(get("/api/offers/{id}", offer.getId())
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewCount").value(2))
        .andExpect(jsonPath("$.clickCount").value(1))
        .andExpect(jsonPath("$.likeCount").value(3));
  }

  @Test
  void viewClickYLikeDeUnaOfertaInexistenteDevuelven404SinRomperNada() throws Exception {
    mockMvc.perform(post("/api/public/offers/{id}/view", 999999)).andExpect(status().isNotFound());
    mockMvc.perform(post("/api/public/offers/{id}/click", 999999)).andExpect(status().isNotFound());
    mockMvc.perform(post("/api/public/offers/{id}/like", 999999)).andExpect(status().isNotFound());
  }

  // --- likeCount / inquiryCount en el DTO público (fase 3) ---

  @Test
  void likeCountEInquiryCountApareceEnElDetallePublicoComoIntCrudoSinGate() throws Exception {
    Business business = persistBusiness("Comercio Like Detalle Test", "098444020");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/like", offer.getId())).andExpect(status().isNoContent());

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(1))
        .andExpect(jsonPath("$.inquiryCount").value(0));
  }

  @Test
  void likeCountEInquiryCountApareceEnElListadoPublico() throws Exception {
    Business business = persistBusiness("Comercio Like Listado Test", "098444021");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(post("/api/public/offers/{id}/like", offer.getId())).andExpect(status().isNoContent());
    mockMvc.perform(post("/api/public/offers/{id}/like", offer.getId())).andExpect(status().isNoContent());

    MvcResult res = mockMvc.perform(get("/api/public/offers").param("zone", "Solymar"))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> likeCounts = com.jayway.jsonpath.JsonPath.read(
        res.getResponse().getContentAsString(), "$[?(@.id == " + offer.getId() + ")].likeCount");
    assertThat(likeCounts).containsExactly(2);
  }

  @Test
  void businessAddressSeExponeCuandoElComercioLaCargo() throws Exception {
    Business business = persistBusiness("Comercio Con Direccion Test", "098444016");
    business.setAddress("Av. Giannattasio km 20, Solymar");
    businessRepository.save(business);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessAddress").value("Av. Giannattasio km 20, Solymar"));
  }

  @Test
  void businessAddressAusenteQuedaNullEnElDtoPublico() throws Exception {
    Business business = persistBusiness("Comercio Sin Direccion Test", "098444017");
    // address queda null — default, no se setea.
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessAddress").doesNotExist());
  }

  @Test
  void viewCountQuedaNullDebajoDelUmbralDeSocialProof() throws Exception {
    Business business = persistBusiness("Comercio Views Bajo Umbral Test", "098444018");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    for (int i = 0; i < 5; i++) {
      mockMvc.perform(post("/api/public/offers/{id}/view", offer.getId())).andExpect(status().isNoContent());
    }

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewCount").doesNotExist());
  }

  // --- Ranking de conveniencia (OfferRankingService, fase 1) ---

  @Test
  void listPublicDevuelveElOrdenDelRankingNoElCronologico() throws Exception {
    // "Barrio primero": a paridad de vigencia/descuento, una oferta local
    // (manual/whatsapp_forward) le gana a una scrapeada, aunque la
    // scrapeada sea más nueva (createdAt más reciente).
    Business localBusiness = persistBusiness("Comercio Ranking Local Test", "098444030");
    Business scrapedBusiness = persistBusiness("Comercio Ranking Scraped Test", "scraped:comercio-ranking-scraped-test");

    // validUntil lejos de cualquier ventana de urgencia, para aislar la señal de origen.
    OffsetDateTime validUntil = OffsetDateTime.now().plusDays(10);

    Offer scraped = persistOffer(scrapedBusiness, OfferStatus.ACTIVE, "otro", "Solymar", validUntil);
    scraped.setOrigin(Offer.ORIGIN_SCRAPED_SOURCE);
    offerRepository.save(scraped);

    Offer local = persistOffer(localBusiness, OfferStatus.ACTIVE, "otro", "Solymar", validUntil);
    local.setOrigin(Offer.ORIGIN_MANUAL);
    offerRepository.save(local);

    MvcResult res = mockMvc.perform(get("/api/public/offers").param("zone", "Solymar"))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> ids = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$[*].id");

    int localIndex = ids.indexOf(local.getId().intValue());
    int scrapedIndex = ids.indexOf(scraped.getId().intValue());
    assertThat(localIndex).isGreaterThanOrEqualTo(0);
    assertThat(scrapedIndex).isGreaterThanOrEqualTo(0);
    assertThat(localIndex).isLessThan(scrapedIndex);
  }

  @Test
  void listPublicPrioriazaUnaOfertaPorVencerSobreUnaScrapedSinUrgencia() throws Exception {
    Business urgenteBusiness = persistBusiness("Comercio Ranking Urgente Test", "scraped:comercio-ranking-urgente-test");
    Offer urgente = persistOffer(urgenteBusiness, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusHours(10));
    urgente.setOrigin(Offer.ORIGIN_SCRAPED_SOURCE);
    urgente.setDiscountText(null);
    offerRepository.save(urgente);

    Business lejanaBusiness = persistBusiness("Comercio Ranking Lejana Test", "098444031");
    Offer lejana = persistOffer(lejanaBusiness, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(10));
    lejana.setOrigin(Offer.ORIGIN_MANUAL);
    lejana.setDiscountText(null);
    offerRepository.save(lejana);

    // Urgencia (+30) le gana a Barrio primero (+25) sola — el orden total combina ambas señales.
    MvcResult res = mockMvc.perform(get("/api/public/offers").param("zone", "Solymar"))
        .andExpect(status().isOk())
        .andReturn();
    List<Integer> ids = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$[*].id");

    assertThat(ids.indexOf(urgente.getId().intValue()))
        .isLessThan(ids.indexOf(lejana.getId().intValue()));
  }

  @Test
  void viewCountMuestraElValorRealEnOSobreElUmbralDeSocialProof() throws Exception {
    Business business = persistBusiness("Comercio Views Sobre Umbral Test", "098444019");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    for (int i = 0; i < 10; i++) {
      mockMvc.perform(post("/api/public/offers/{id}/view", offer.getId())).andExpect(status().isNoContent());
    }

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewCount").value(10));
  }
}
