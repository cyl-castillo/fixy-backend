package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SitemapService}: home + /ofertas siempre presentes, y una entrada
 * por cada oferta ACTIVE y vigente (mismo criterio que
 * {@code OfferService.listPublic}). Cada aserción filtra por el id de la
 * oferta creada en ESTE test — H2 compartida entre contextos, no asumir
 * sitemap vacío (lección conocida del repo).
 */
@SpringBootTest
@Transactional
class SitemapServiceTest {

  @Autowired private SitemapService sitemapService;
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

  private Offer persistOffer(Business business, String title, OfferStatus status, OffsetDateTime validUntil) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle(title);
    offer.setCategory("otro");
    offer.setZone("Solymar");
    offer.setStatus(status);
    offer.setValidUntil(validUntil);
    return offerRepository.save(offer);
  }

  @Test
  void incluyeSiempreHomeYOfertas() {
    String xml = sitemapService.render();

    assertThat(xml).contains("<loc>https://www.fixy.com.uy/</loc>");
    assertThat(xml).contains("<loc>https://www.fixy.com.uy/ofertas</loc>");
  }

  @Test
  void incluyeUnaOfertaActivaYVigenteConSuLastmod() {
    Business business = persistBusiness("Comercio Sitemap Test", "098666001");
    Offer offer = persistOffer(business, "Oferta sitemap vigente", OfferStatus.ACTIVE,
        OffsetDateTime.now().plusDays(5));
    // Releer el updatedAt tal como quedó persistido (evita desajustes de
    // precisión entre el objeto en memoria y lo que devuelve la consulta
    // real que usa el servicio).
    OffsetDateTime updatedAt = offerRepository.findById(offer.getId()).orElseThrow().getUpdatedAt();
    String expectedLastmod = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(updatedAt);

    String xml = sitemapService.render();

    assertThat(xml).contains("<loc>https://www.fixy.com.uy/oferta/" + offer.getId() + "</loc>");
    assertThat(xml).contains("<lastmod>" + expectedLastmod + "</lastmod>");
  }

  @Test
  void noIncluyeOfertaDraft() {
    Business business = persistBusiness("Comercio Sitemap Draft Test", "098666002");
    Offer offer = persistOffer(business, "Oferta sitemap draft", OfferStatus.DRAFT,
        OffsetDateTime.now().plusDays(5));

    String xml = sitemapService.render();

    assertThat(xml).doesNotContain("<loc>https://www.fixy.com.uy/oferta/" + offer.getId() + "</loc>");
  }

  @Test
  void noIncluyeOfertaActivaPeroVencidaPorFecha() {
    Business business = persistBusiness("Comercio Sitemap Vencida Test", "098666003");
    Offer offer = persistOffer(business, "Oferta sitemap vencida", OfferStatus.ACTIVE,
        OffsetDateTime.now().minusHours(1));

    String xml = sitemapService.render();

    assertThat(xml).doesNotContain("<loc>https://www.fixy.com.uy/oferta/" + offer.getId() + "</loc>");
  }

  @Test
  void noIncluyeOfertaRechazada() {
    Business business = persistBusiness("Comercio Sitemap Rechazada Test", "098666004");
    Offer offer = persistOffer(business, "Oferta sitemap rechazada", OfferStatus.REJECTED,
        OffsetDateTime.now().plusDays(5));

    String xml = sitemapService.render();

    assertThat(xml).doesNotContain("<loc>https://www.fixy.com.uy/oferta/" + offer.getId() + "</loc>");
  }
}
