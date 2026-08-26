package com.fixy.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessCatalogItem;
import com.fixy.backend.model.BusinessHour;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessHourRepository;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import com.fixy.backend.service.BusinessSlugService;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /og/comercio/{slug}} — endpoint público para bots de preview
 * (WhatsApp/Meta no ejecutan JS) con JSON-LD {@code LocalBusiness} (Fase 3,
 * gap analysis 2026-08-25 §3 punto 4). {@code fixy.frontend.index-path} no
 * está seteado en test, así que estos tests ejercitan el fallback HTML
 * embebido de {@link com.fixy.backend.service.BusinessOgHtmlService}, mismo
 * criterio que {@code OfferOgControllerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusinessOgControllerTest {

  private static final Pattern JSON_LD_PATTERN = Pattern.compile(
      "<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL);

  @Autowired private MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private BusinessHourRepository businessHourRepository;
  @Autowired private OfferRepository offerRepository;
  @Autowired private BusinessSlugService businessSlugService;

  private Business persistBusiness(String name, String whatsapp) {
    Business business = new Business();
    business.setName(name);
    business.setWhatsappNumber(whatsapp);
    business.setCategory("ferreteria");
    business.setPrimaryZone("Solymar");
    business.setAddress("Av. Giannattasio km 20");
    business.setLatitude(-34.8);
    business.setLongitude(-55.9);
    business.setDescription("Ferretería de barrio con pinturería");
    business.setStatus(BusinessStatus.ACTIVE);
    return businessRepository.save(business);
  }

  private void persistHour(Business business, int dayOfWeek, String opens, String closes) {
    BusinessHour hour = new BusinessHour();
    hour.setBusiness(business);
    hour.setDayOfWeek((short) dayOfWeek);
    hour.setOpensAt(opens);
    hour.setClosesAt(closes);
    businessHourRepository.save(hour);
  }

  private Offer persistOffer(Business business, String title, OfferStatus status, OffsetDateTime validUntil) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle(title);
    offer.setCategory("ferreteria");
    offer.setStatus(status);
    offer.setValidUntil(validUntil);
    return offerRepository.save(offer);
  }

  private String jsonLdOf(String body) {
    Matcher matcher = JSON_LD_PATTERN.matcher(body);
    assertThat(matcher.find()).as("debe haber un <script type=application/ld+json>").isTrue();
    return matcher.group(1).replace("<\\/", "</");
  }

  @Test
  void comercioActivoConSlugDevuelveMetaOgYJsonLdLocalBusiness() throws Exception {
    Business business = persistBusiness("Ferretería OG Test", "098900001");
    String slug = businessSlugService.ensureSlug(business);
    persistHour(business, 1, "09:00", "18:00");
    persistHour(business, 2, "09:00", "13:00");
    Offer offer = persistOffer(business, "20% en pinturas", OfferStatus.ACTIVE, OffsetDateTime.now().plusDays(5));

    MvcResult res = mockMvc.perform(get("/og/comercio/{slug}", slug))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andReturn();

    String body = res.getResponse().getContentAsString();
    // HtmlUtils.htmlEscape entity-codifica tildes y el guion largo — HTML válido,
    // se compara contra la misma función (mismo criterio que OfferOgControllerTest).
    assertThat(body).contains(
        "<meta property=\"og:title\" content=\""
            + org.springframework.web.util.HtmlUtils.htmlEscape("Ferretería OG Test — ferreteria en Solymar | Fixy")
            + "\" />");
    assertThat(body).contains(
        "<meta property=\"og:url\" content=\"https://www.fixy.com.uy/comercio/" + slug + "\" />");
    assertThat(body).contains(
        "<link rel=\"canonical\" href=\"https://www.fixy.com.uy/comercio/" + slug + "\" />");

    String json = jsonLdOf(body);
    assertThat(JsonPath.<String>read(json, "$.@type")).isEqualTo("LocalBusiness");
    assertThat(JsonPath.<String>read(json, "$.name")).isEqualTo("Ferretería OG Test");
    assertThat(JsonPath.<String>read(json, "$.description")).isEqualTo("Ferretería de barrio con pinturería");
    assertThat(JsonPath.<String>read(json, "$.address.streetAddress")).isEqualTo("Av. Giannattasio km 20");
    assertThat(JsonPath.<String>read(json, "$.address.addressLocality")).isEqualTo("Solymar");
    assertThat(JsonPath.<String>read(json, "$.address.addressCountry")).isEqualTo("UY");
    assertThat(JsonPath.<Double>read(json, "$.geo.latitude")).isEqualTo(-34.8);
    assertThat(JsonPath.<Double>read(json, "$.geo.longitude")).isEqualTo(-55.9);
    assertThat(JsonPath.<String>read(json, "$.url"))
        .isEqualTo("https://www.fixy.com.uy/comercio/" + slug);
    assertThat(JsonPath.<String>read(json, "$.areaServed")).isEqualTo("Solymar");

    List<Map<String, Object>> hours = JsonPath.read(json, "$.openingHoursSpecification");
    assertThat(hours).hasSize(2);
    assertThat(hours.get(0).get("@type")).isEqualTo("OpeningHoursSpecification");
    assertThat(hours.get(0).get("dayOfWeek")).isEqualTo("Monday");
    assertThat(hours.get(0).get("opens")).isEqualTo("09:00");
    assertThat(hours.get(0).get("closes")).isEqualTo("18:00");
    assertThat(hours.get(1).get("dayOfWeek")).isEqualTo("Tuesday");

    List<Map<String, Object>> offers = JsonPath.read(json, "$.makesOffer");
    assertThat(offers).hasSize(1);
    assertThat(offers.get(0).get("@type")).isEqualTo("Offer");
    assertThat(offers.get(0).get("name")).isEqualTo("20% en pinturas");
    assertThat(offers.get(0).get("validThrough")).isNotNull();
    // el propio id de la oferta no debería filtrarse, solo su título/vigencia.
    assertThat(offers.get(0)).doesNotContainKey("id");
  }

  @Test
  void ofertaVencidaNoApareceEnMakesOffer() throws Exception {
    Business business = persistBusiness("Ferretería OG Sin Ofertas Vigentes Test", "098900002");
    String slug = businessSlugService.ensureSlug(business);
    persistOffer(business, "Oferta ya vencida", OfferStatus.ACTIVE, OffsetDateTime.now().minusHours(1));
    persistOffer(business, "Oferta draft", OfferStatus.DRAFT, OffsetDateTime.now().plusDays(5));

    MvcResult res = mockMvc.perform(get("/og/comercio/{slug}", slug))
        .andExpect(status().isOk())
        .andReturn();

    String json = jsonLdOf(res.getResponse().getContentAsString());
    // Sin ofertas vigentes, la clave se omite entera (contrato: solo se
    // agrega si hay al menos una) — JsonPath.read tira PathNotFound si se
    // intenta leerla, así que se verifica por ausencia en el JSON crudo.
    assertThat(json).doesNotContain("\"makesOffer\"");
  }

  @Test
  void comercioSinCoordenadasNoIncluyeGeo() throws Exception {
    Business business = new Business();
    business.setName("Comercio OG Sin Coordenadas Test");
    business.setWhatsappNumber("098900003");
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    business = businessRepository.save(business);
    String slug = businessSlugService.ensureSlug(business);

    MvcResult res = mockMvc.perform(get("/og/comercio/{slug}", slug))
        .andExpect(status().isOk())
        .andReturn();

    String json = jsonLdOf(res.getResponse().getContentAsString());
    assertThat(json).doesNotContain("\"geo\"");
  }

  @Test
  void comercioInexistenteDevuelve200ConElShellGenericoSinJsonLd() throws Exception {
    MvcResult res = mockMvc.perform(get("/og/comercio/{slug}", "no-existe-este-comercio-og"))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).contains("<title>Fixy — Servicios del hogar en Ciudad de la Costa</title>");
    assertThat(body).doesNotContain("application/ld+json");
    assertThat(body).doesNotContain("og:title");
  }

  @Test
  void comercioInactivoDevuelve200ConElShellGenericoSinJsonLd() throws Exception {
    Business business = persistBusiness("Comercio OG Inactivo Test", "098900004");
    String slug = businessSlugService.ensureSlug(business);
    business.setStatus(BusinessStatus.INACTIVE);
    businessRepository.save(business);

    MvcResult res = mockMvc.perform(get("/og/comercio/{slug}", slug))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).doesNotContain("Comercio OG Inactivo Test");
    assertThat(body).doesNotContain("application/ld+json");
  }

  @Test
  void esPublicoSinCredencialesDeAuth() throws Exception {
    mockMvc.perform(get("/og/comercio/{slug}", "no-existe-este-comercio-og-auth"))
        .andExpect(status().isOk());
  }
}
