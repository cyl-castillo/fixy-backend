package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Wiring end-to-end del campo {@code analysis} en {@code OfferPublicResponse}
 * (fase 3, ver {@code OfferAnalysisService}) a través de HTTP real, con H2 —
 * categorías con nombre único por test (lección conocida del repo: H2
 * compartida entre contextos, otros tests ya usan la categoría "otro" a
 * destajo, así que "bestOfCategory"/"firstTimeInWeeks" quedarían
 * contaminados si reusáramos esa categoría).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OfferAnalysisWiringTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private OfferRepository offerRepository;
  @Autowired private BusinessRepository businessRepository;

  private Business persistBusiness(String name) {
    Business business = new Business();
    business.setName(name);
    business.setWhatsappNumber("09" + System.nanoTime() % 100_000_000L);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    return businessRepository.save(business);
  }

  private Offer persistOffer(Business business, OfferStatus status, String category, String discountText) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle("Oferta análisis test " + business.getId());
    offer.setCategory(category);
    offer.setZone("Solymar");
    offer.setAllZones(true);
    offer.setDiscountText(discountText);
    offer.setStatus(status);
    offer.setValidUntil(OffsetDateTime.now().plusDays(30));
    return offerRepository.save(offer);
  }

  /** Fija {@code createdAt} pisando el valor real de {@code @PrePersist} — requiere un segundo save (solo dispara @PreUpdate). */
  private Offer withCreatedAt(Offer offer, OffsetDateTime createdAt) {
    offer.setCreatedAt(createdAt);
    return offerRepository.save(offer);
  }

  @Test
  void elListadoPublicoSiempreIncluyeElObjetoAnalysis() throws Exception {
    Business business = persistBusiness("Comercio Analysis Listado Test");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "cat-analysis-listado-basico", "20% off");

    MvcResult res = mockMvc.perform(get("/api/public/offers").param("category", "cat-analysis-listado-basico"))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).contains("\"analysis\"");
    List<Integer> discountPercents = com.jayway.jsonpath.JsonPath.read(
        body, "$[?(@.id == " + offer.getId() + ")].analysis.discountPercent");
    assertThat(discountPercents).containsExactly(20);
  }

  @Test
  void elDetallePublicoIncluyeElObjetoAnalysis() throws Exception {
    Business business = persistBusiness("Comercio Analysis Detalle Test");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "cat-analysis-detalle-basico", "2x1");

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.discountPercent").value(50))
        .andExpect(jsonPath("$.analysis.bestOfCategory").value(false)) // única vigente de la categoría.
        .andExpect(jsonPath("$.analysis.firstTimeInWeeks").doesNotExist());
  }

  @Test
  void discountPercentNullCuandoElTextoNoEsComparable() throws Exception {
    Business business = persistBusiness("Comercio Analysis Sin Descuento Test");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "cat-analysis-sin-descuento", "Envío gratis");

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.discountPercent").doesNotExist())
        .andExpect(jsonPath("$.analysis.bestOfCategory").value(false))
        .andExpect(jsonPath("$.analysis.firstTimeInWeeks").doesNotExist());
  }

  @Test
  void bestOfCategorySoloParaLaDeMayorDescuentoEntreDosVigentes() throws Exception {
    Business business = persistBusiness("Comercio Analysis Best Test");
    Offer bajo = persistOffer(business, OfferStatus.ACTIVE, "cat-analysis-best-dos", "10% off");
    Offer alto = persistOffer(business, OfferStatus.ACTIVE, "cat-analysis-best-dos", "40% off");

    mockMvc.perform(get("/api/public/offers/{id}", alto.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.bestOfCategory").value(true));

    mockMvc.perform(get("/api/public/offers/{id}", bajo.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.bestOfCategory").value(false));
  }

  @Test
  void bestOfCategoryEnEmpateGanaLaMasVieja() throws Exception {
    Business business = persistBusiness("Comercio Analysis Empate Test");
    Offer primera = persistOffer(business, OfferStatus.ACTIVE, "cat-analysis-empate", "20% off");
    Offer segunda = persistOffer(business, OfferStatus.ACTIVE, "cat-analysis-empate", "20% off");
    // Aseguramos el orden temporal explícitamente (no confiar en el reloj real entre dos saves consecutivos).
    withCreatedAt(primera, OffsetDateTime.now().minusDays(10));
    withCreatedAt(segunda, OffsetDateTime.now().minusDays(1));

    mockMvc.perform(get("/api/public/offers/{id}", primera.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.bestOfCategory").value(true));

    mockMvc.perform(get("/api/public/offers/{id}", segunda.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.bestOfCategory").value(false));
  }

  @Test
  void firstTimeInWeeksCuentaDesdeUnaOfertaVencidaDelHistorial() throws Exception {
    // Mismo "now" de referencia para ambos timestamps — evita que dos
    // llamadas separadas a OffsetDateTime.now() difieran por milisegundos y
    // hagan caer la duración justo por debajo de las 21 * 24h exactas
    // (Duration.toDays() trunca, así que eso bajaría el resultado a 2 semanas).
    OffsetDateTime now = OffsetDateTime.now();
    Business business = persistBusiness("Comercio Analysis Historial Test");
    Offer vieja = persistOffer(business, OfferStatus.EXPIRED, "cat-analysis-historial", "20% off");
    withCreatedAt(vieja, now.minusDays(21));

    Offer actual = persistOffer(business, OfferStatus.ACTIVE, "cat-analysis-historial", "20% off");
    withCreatedAt(actual, now);

    mockMvc.perform(get("/api/public/offers/{id}", actual.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.firstTimeInWeeks").value(3));
  }

  @Test
  void firstTimeInWeeksNullSinNingunaOfertaAnteriorEnLaCategoria() throws Exception {
    Business business = persistBusiness("Comercio Analysis Sin Historial Test");
    Offer offer = persistOffer(business, OfferStatus.ACTIVE, "cat-analysis-sin-historial-previo", "20% off");

    mockMvc.perform(get("/api/public/offers/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.analysis.firstTimeInWeeks").doesNotExist());
  }
}
