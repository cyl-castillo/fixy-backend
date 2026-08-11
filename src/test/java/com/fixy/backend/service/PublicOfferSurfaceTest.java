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
  void viewYClickSonPublicosYSumanElContadorVisibleEnElDtoAdmin() throws Exception {
    Business business = persistBusiness("Comercio Metrica Test", "098444007");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "otro", "Solymar", OffsetDateTime.now().plusDays(5));

    // Sin httpBasic: son endpoints públicos.
    mockMvc.perform(post("/api/public/offers/{id}/view", offer.getId())).andExpect(status().isNoContent());
    mockMvc.perform(post("/api/public/offers/{id}/view", offer.getId())).andExpect(status().isNoContent());
    mockMvc.perform(post("/api/public/offers/{id}/click", offer.getId())).andExpect(status().isNoContent());

    mockMvc.perform(get("/api/offers/{id}", offer.getId())
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewCount").value(2))
        .andExpect(jsonPath("$.clickCount").value(1));
  }

  @Test
  void viewYClickDeUnaOfertaInexistenteDevuelven404SinRomperNada() throws Exception {
    mockMvc.perform(post("/api/public/offers/{id}/view", 999999)).andExpect(status().isNotFound());
    mockMvc.perform(post("/api/public/offers/{id}/click", 999999)).andExpect(status().isNotFound());
  }
}
