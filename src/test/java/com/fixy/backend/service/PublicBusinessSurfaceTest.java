package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessCatalogItem;
import com.fixy.backend.model.BusinessCatalogItemConfidence;
import com.fixy.backend.model.BusinessCatalogItemKind;
import com.fixy.backend.model.BusinessHour;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessCatalogItemRepository;
import com.fixy.backend.repository.BusinessHourRepository;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /api/public/businesses/{slug}} (Fase 3 de la mutación hacia
 * ficha, gap analysis 2026-08-25 §3): página pública del comercio. Cada test
 * usa su propio slug único (mismo criterio de aislamiento de H2 compartida
 * entre contextos que el resto de la suite).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicBusinessSurfaceTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private BusinessHourRepository businessHourRepository;
  @Autowired private BusinessCatalogItemRepository businessCatalogItemRepository;
  @Autowired private OfferRepository offerRepository;
  @Autowired private BusinessSlugService businessSlugService;

  private Business persistBusiness(String name, String whatsapp) {
    Business business = new Business();
    business.setName(name);
    business.setWhatsappNumber(whatsapp);
    business.setCategory("ferreteria");
    business.setCategories("ferreteria, pinturas");
    business.setPrimaryZone("Solymar");
    business.setAddress("Av. Giannattasio km 20");
    business.setLatitude(-34.8);
    business.setLongitude(-55.9);
    business.setDescription("Ferretería de barrio");
    business.setStatus(BusinessStatus.ACTIVE);
    return businessRepository.save(business);
  }

  private BusinessHour persistHour(Business business, int dayOfWeek, String opens, String closes) {
    BusinessHour hour = new BusinessHour();
    hour.setBusiness(business);
    hour.setDayOfWeek((short) dayOfWeek);
    hour.setOpensAt(opens);
    hour.setClosesAt(closes);
    return businessHourRepository.save(hour);
  }

  private BusinessCatalogItem persistCatalogItem(
      Business business, String label, BusinessCatalogItemConfidence confidence, boolean active, boolean available
  ) {
    BusinessCatalogItem item = new BusinessCatalogItem();
    item.setBusiness(business);
    item.setLabel(label);
    item.setKind(BusinessCatalogItemKind.PRODUCT);
    item.setConfidence(confidence);
    item.setActive(active);
    item.setAvailable(available);
    return businessCatalogItemRepository.save(item);
  }

  private Offer persistOffer(Business business, OfferStatus status, OffsetDateTime validUntil) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle("Oferta ficha pública test " + business.getId());
    offer.setCategory("ferreteria");
    offer.setStatus(status);
    offer.setValidUntil(validUntil);
    return offerRepository.save(offer);
  }

  @Test
  void devuelve404OpacoSiElSlugNoExiste() throws Exception {
    mockMvc.perform(get("/api/public/businesses/{slug}", "no-existe-este-comercio"))
        .andExpect(status().isNotFound());
  }

  @Test
  void devuelve404OpacoSiElComercioEstaInactivoAunqueTengaSlug() throws Exception {
    Business business = persistBusiness("Comercio Inactivo Ficha Test", "098800001");
    String slug = businessSlugService.ensureSlug(business);
    business.setStatus(BusinessStatus.INACTIVE);
    businessRepository.save(business);

    mockMvc.perform(get("/api/public/businesses/{slug}", slug))
        .andExpect(status().isNotFound());
  }

  @Test
  void devuelve404OpacoSiElComercioTodaviaNoTieneSlug() throws Exception {
    // Nunca se llamó ensureSlug — comercio activo pero sin slug asignado.
    persistBusiness("Comercio Sin Slug Ficha Test", "098800002");

    mockMvc.perform(get("/api/public/businesses/{slug}", "comercio-sin-slug-ficha-test"))
        .andExpect(status().isNotFound());
  }

  @Test
  void devuelveLaFichaCompletaDeUnComercioActivoConSlug() throws Exception {
    Business business = persistBusiness("Comercio Ficha Completa Test", "098800003");
    String slug = businessSlugService.ensureSlug(business);
    persistHour(business, 1, "09:00", "18:00");
    BusinessCatalogItem catalogItem =
        persistCatalogItem(business, "Taladro Bosch", BusinessCatalogItemConfidence.CONFIRMADO, true, true);
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    mockMvc.perform(get("/api/public/businesses/{slug}", slug))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(business.getId()))
        .andExpect(jsonPath("$.slug").value(slug))
        .andExpect(jsonPath("$.name").value("Comercio Ficha Completa Test"))
        .andExpect(jsonPath("$.category").value("ferreteria"))
        .andExpect(jsonPath("$.categories").value("ferreteria, pinturas"))
        .andExpect(jsonPath("$.primaryZone").value("Solymar"))
        .andExpect(jsonPath("$.address").value("Av. Giannattasio km 20"))
        .andExpect(jsonPath("$.latitude").value(-34.8))
        .andExpect(jsonPath("$.longitude").value(-55.9))
        .andExpect(jsonPath("$.description").value("Ferretería de barrio"))
        .andExpect(jsonPath("$.hours[0].dayOfWeek").value(1))
        .andExpect(jsonPath("$.hours[0].opensAt").value("09:00"))
        .andExpect(jsonPath("$.catalog[0].id").value(catalogItem.getId()))
        .andExpect(jsonPath("$.catalog[0].label").value("Taladro Bosch"))
        .andExpect(jsonPath("$.catalog[0].confidence").value("CONFIRMADO"))
        .andExpect(jsonPath("$.offers[0].id").value(offer.getId()));
  }

  @Test
  void nuncaExponeWhatsappNumberNiPanelToken() throws Exception {
    Business business = persistBusiness("Comercio Sin Contacto Ficha Test", "098800004");
    business.setPanelToken("panel-token-no-debe-salir-nunca");
    businessRepository.save(business);
    String slug = businessSlugService.ensureSlug(business);

    MvcResult res = mockMvc.perform(get("/api/public/businesses/{slug}", slug))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).doesNotContain("098800004");
    assertThat(body).doesNotContain("whatsappNumber");
    assertThat(body).doesNotContain("panelToken");
    assertThat(body).doesNotContain("panel-token-no-debe-salir-nunca");
  }

  @Test
  void elCatalogoIncluyeItemsDeCualquierConfianzaIncluidosNoDisponiblesPeroNoInactivos() throws Exception {
    Business business = persistBusiness("Comercio Catalogo Ficha Test", "098800005");
    String slug = businessSlugService.ensureSlug(business);
    persistCatalogItem(business, "Item Declarado", BusinessCatalogItemConfidence.DECLARADO, true, true);
    persistCatalogItem(business, "Item No Disponible", BusinessCatalogItemConfidence.CONFIRMADO, true, false);
    persistCatalogItem(business, "Item Inactivo", BusinessCatalogItemConfidence.DECLARADO, false, true);

    MvcResult res = mockMvc.perform(get("/api/public/businesses/{slug}", slug))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).contains("Item Declarado");
    assertThat(body).contains("Item No Disponible");
    assertThat(body).doesNotContain("Item Inactivo");
  }

  @Test
  void soloIncluyeOfertasActivasYVigentes() throws Exception {
    Business business = persistBusiness("Comercio Ofertas Ficha Test", "098800006");
    String slug = businessSlugService.ensureSlug(business);
    Offer active = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));
    Offer draft = persistOffer(business, OfferStatus.DRAFT, OffsetDateTime.now().plusDays(5));
    Offer expiradaPorFecha = persistOffer(business, OfferStatus.ACTIVE, OffsetDateTime.now().minusHours(1));

    MvcResult res = mockMvc.perform(get("/api/public/businesses/{slug}", slug))
        .andExpect(status().isOk())
        .andReturn();

    java.util.List<Integer> ids = com.jayway.jsonpath.JsonPath.read(
        res.getResponse().getContentAsString(), "$.offers[*].id");
    assertThat(ids).contains(active.getId().intValue());
    assertThat(ids).doesNotContain(draft.getId().intValue(), expiradaPorFecha.getId().intValue());
  }

  @Test
  void viewCountQuedaNullDebajoDelUmbralDeSocialProof() throws Exception {
    Business business = persistBusiness("Comercio Views Bajo Umbral Ficha Test", "098800007");
    String slug = businessSlugService.ensureSlug(business);

    for (int i = 0; i < 3; i++) {
      mockMvc.perform(get("/api/public/businesses/{slug}", slug)).andExpect(status().isOk());
    }

    mockMvc.perform(get("/api/public/businesses/{slug}", slug))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewCount").doesNotExist());
  }

  @Test
  void viewCountMuestraElValorRealCuandoSuperaElUmbral() throws Exception {
    Business business = persistBusiness("Comercio Views Sobre Umbral Ficha Test", "098800008");
    String slug = businessSlugService.ensureSlug(business);

    // El umbral default es 10 — 10 GETs previos dejan el contador en 10,
    // visible en el siguiente (cada GET incrementa fire-and-forget).
    for (int i = 0; i < 10; i++) {
      mockMvc.perform(get("/api/public/businesses/{slug}", slug)).andExpect(status().isOk());
    }

    mockMvc.perform(get("/api/public/businesses/{slug}", slug))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewCount").value(10));
  }

  @Test
  void cadaGetIncrementaElContadorDeVistasEnLaEntidad() throws Exception {
    Business business = persistBusiness("Comercio Contador Vistas Ficha Test", "098800009");
    String slug = businessSlugService.ensureSlug(business);

    mockMvc.perform(get("/api/public/businesses/{slug}", slug)).andExpect(status().isOk());
    mockMvc.perform(get("/api/public/businesses/{slug}", slug)).andExpect(status().isOk());

    Business reloaded = businessRepository.findById(business.getId()).orElseThrow();
    assertThat(reloaded.getViewCount()).isEqualTo(2L);
  }

  @Test
  void esPublicoSinCredencialesDeAuth() throws Exception {
    Business business = persistBusiness("Comercio Publico Sin Auth Ficha Test", "098800010");
    String slug = businessSlugService.ensureSlug(business);

    mockMvc.perform(get("/api/public/businesses/{slug}", slug))
        .andExpect(status().isOk());
  }
}
