package com.fixy.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
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
import org.springframework.web.util.HtmlUtils;

/**
 * {@code GET /og/oferta/{id}} — endpoint público para bots de preview de
 * links (WhatsApp/Meta no ejecutan JS). {@code fixy.frontend.index-path} no
 * está seteado en test (no hay un release de fixy-app en este entorno), así
 * que estos tests ejercitan naturalmente el fallback HTML embebido de
 * {@link com.fixy.backend.service.OfferOgHtmlService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OfferOgControllerTest {

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

  private Offer persistOffer(Business business, String title) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle(title);
    offer.setCategory("otro");
    offer.setZone("Solymar");
    offer.setDiscountText("20% off");
    offer.setStatus(OfferStatus.ACTIVE);
    offer.setValidUntil(OffsetDateTime.now().plusDays(5));
    return offerRepository.save(offer);
  }

  @Test
  void ofertaActivaConFotoDevuelveMetaOgConLosDatosReales() throws Exception {
    Business business = persistBusiness("Panadería OG Test", "098555001");
    Offer offer = persistOffer(business, "20% en tortas");
    offer.setPhotoUrl("https://api.fixy.com.uy/uploads/offer-1/foto.jpg");
    offerRepository.save(offer);

    MvcResult res = mockMvc.perform(get("/og/oferta/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andReturn();

    String body = res.getResponse().getContentAsString();
    // og:title = título + descuento (contrato de OfferOgHtmlService.buildTitle):
    // el beneficio tiene que verse en lo que WhatsApp muestra más grande.
    // HtmlUtils.htmlEscape entity-codifica el separador "·" — se compara
    // contra la misma función, mismo criterio que la aserción de og:description.
    assertThat(body).contains(
        "<meta property=\"og:title\" content=\""
            + HtmlUtils.htmlEscape("20% en tortas · 20% off") + "\" />");
    assertThat(body).contains(
        "<meta property=\"og:image\" content=\"https://api.fixy.com.uy/uploads/offer-1/foto.jpg\" />");
    assertThat(body).contains(
        "<meta property=\"og:url\" content=\"https://www.fixy.com.uy/oferta/" + offer.getId() + "\" />");
    assertThat(body).contains("<meta name=\"twitter:card\" content=\"summary_large_image\" />");
    // HtmlUtils.htmlEscape entity-codifica tildes y el separador "·" — HTML válido,
    // se compara contra la misma función para no duplicar la tabla de entidades acá.
    assertThat(body).contains(
        "<meta property=\"og:description\" content=\""
            + HtmlUtils.htmlEscape("20% off · Panadería OG Test · Solymar") + "\" />");
  }

  @Test
  void ofertaSinFotoUsaLaImagenDefaultDeMarca() throws Exception {
    Business business = persistBusiness("Comercio Sin Foto OG Test", "098555002");
    Offer offer = persistOffer(business, "10% en jardinería");

    MvcResult res = mockMvc.perform(get("/og/oferta/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).contains(
        "<meta property=\"og:image\" content=\"https://api.fixy.com.uy/images/og-default.png\" />");
  }

  @Test
  void ofertaSinDescuentoUsaSoloElTituloEnOgTitle() throws Exception {
    Business business = persistBusiness("Comercio Sin Descuento OG Test", "098555007");
    Offer offer = persistOffer(business, "Envío gratis en el local");
    offer.setDiscountText(null);
    offerRepository.save(offer);

    MvcResult res = mockMvc.perform(get("/og/oferta/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).contains(
        "<meta property=\"og:title\" content=\""
            + HtmlUtils.htmlEscape("Envío gratis en el local") + "\" />");
  }

  @Test
  void tituloQueYaContieneElDescuentoNoLoRepiteEnOgTitle() throws Exception {
    Business business = persistBusiness("Comercio Dedup OG Test", "098555008");
    Offer offer = persistOffer(business, "2x1 en muzzarella");
    offer.setDiscountText("2x1");
    offerRepository.save(offer);

    MvcResult res = mockMvc.perform(get("/og/oferta/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andReturn();

    // "2x1 en muzzarella · 2x1" sería redundante — el contrato de buildTitle
    // omite el descuento cuando el título ya lo contiene (case-insensitive).
    String body = res.getResponse().getContentAsString();
    assertThat(body).contains(
        "<meta property=\"og:title\" content=\""
            + HtmlUtils.htmlEscape("2x1 en muzzarella") + "\" />");
  }

  @Test
  void descripcionTerminaConLaFuenteCuandoLaOfertaVieneDeUnaIngesta() throws Exception {
    Business business = persistBusiness("Comercio Fuente OG Test", "098555003");
    Offer offer = persistOffer(business, "Beneficio bancario");
    offer.setSourceName("BROU beneficios");
    offerRepository.save(offer);

    MvcResult res = mockMvc.perform(get("/og/oferta/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).contains(
        "<meta property=\"og:description\" content=\""
            + HtmlUtils.htmlEscape("20% off · Comercio Fuente OG Test · Solymar · Fuente: BROU beneficios")
            + "\" />");
  }

  @Test
  void tituloYDescripcionConCaracteresHtmlQuedanEscapadosSinRomperElMarkup() throws Exception {
    Business business = persistBusiness("Comercio <script>alert(1)</script> \"Test\" & Cía", "098555004");
    Offer offer = persistOffer(business, "<script>alert('xss')</script> & \"ofertón\"");
    offerRepository.save(offer);

    MvcResult res = mockMvc.perform(get("/og/oferta/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).doesNotContain("<script>");
    assertThat(body).contains("&lt;script&gt;");
    assertThat(body).contains("&amp;");
    assertThat(body).contains("&quot;");
  }

  @Test
  void ofertaInexistenteDevuelve200ConElShellGenericoDeFixy() throws Exception {
    MvcResult res = mockMvc.perform(get("/og/oferta/{id}", 999999))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).contains("<title>Fixy — Servicios del hogar en Ciudad de la Costa</title>");
    assertThat(body).doesNotContain("og:title");
  }

  @Test
  void ofertaDraftDevuelve200ConElShellGenericoDeFixy() throws Exception {
    Business business = persistBusiness("Comercio Draft OG Test", "098555005");
    Offer offer = persistOffer(business, "Oferta todavía sin aprobar");
    offer.setStatus(OfferStatus.DRAFT);
    offerRepository.save(offer);

    MvcResult res = mockMvc.perform(get("/og/oferta/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).doesNotContain("Oferta todavía sin aprobar");
    assertThat(body).doesNotContain("og:title");
  }

  @Test
  void ofertaExpiradaPorFechaDevuelve200ConElShellGenericoDeFixy() throws Exception {
    Business business = persistBusiness("Comercio Vencida OG Test", "098555006");
    Offer offer = persistOffer(business, "Oferta ya vencida");
    offer.setValidUntil(OffsetDateTime.now().minusHours(1));
    offerRepository.save(offer);

    MvcResult res = mockMvc.perform(get("/og/oferta/{id}", offer.getId()))
        .andExpect(status().isOk())
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).doesNotContain("Oferta ya vencida");
    assertThat(body).doesNotContain("og:title");
  }

  @Test
  void esPublicoSinCredencialesDeAuth() throws Exception {
    mockMvc.perform(get("/og/oferta/{id}", 999999))
        .andExpect(status().isOk());
  }
}
