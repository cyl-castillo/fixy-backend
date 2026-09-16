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
 * {@link SitemapService}: home + rutas estáticas siempre presentes (Tier 1,
 * contrato §A.5: /ofertas se quitó del listado estático por estar pausado),
 * y una entrada por cada oferta ACTIVE y vigente que exista igual (mismo
 * criterio que {@code OfferService.listPublic}). Cada aserción filtra por
 * el id de la oferta creada en ESTE test — H2 compartida entre contextos,
 * no asumir sitemap vacío (lección conocida del repo).
 */
@SpringBootTest
@Transactional
class SitemapServiceTest {

  @Autowired private SitemapService sitemapService;
  @Autowired private OfferRepository offerRepository;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private BusinessSlugService businessSlugService;

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
  void incluyeSiempreHomeYSumate() {
    String xml = sitemapService.render();

    assertThat(xml).contains("<loc>https://www.fixy.com.uy/</loc>");
    // Puerta única de registro (Fase 3+4, 2026-08-27): la landing de alta de
    // comercios/proveedores es una ruta estática más, mismo patrón que home
    // — siempre presente, sin depender de datos.
    assertThat(xml).contains("<loc>https://www.fixy.com.uy/sumate</loc>");
  }

  @Test
  void noIncluyeLaEntradaEstaticaDeOfertasPausada() {
    // Tier 1 (contrato §A.5): /ofertas está pausado, se quita del sitemap.
    // Una oferta ACTIVE vigente que exista igual mantiene su propia URL
    // (/oferta/{id}, ver test aparte) — lo que se quita es SOLO la entrada
    // estática de listado.
    String xml = sitemapService.render();

    assertThat(xml).doesNotContain("<loc>https://www.fixy.com.uy/ofertas</loc>");
  }

  @Test
  void incluyeTerminosYLasTresPaginasDeServicioMasCasaADistancia() {
    // Tier 1 (contrato §A.2/§A.5): las tres páginas estáticas servicio+zona
    // y /terminos, siempre presentes (no dependen de datos, mismo criterio
    // que /sumate y /casa-a-distancia).
    String xml = sitemapService.render();

    assertThat(xml).contains("<loc>https://www.fixy.com.uy/terminos</loc>");
    assertThat(xml).contains("<loc>https://www.fixy.com.uy/casa-a-distancia</loc>");
    assertThat(xml).contains(
        "<loc>https://www.fixy.com.uy/servicios/aire-acondicionado-ciudad-de-la-costa/</loc>");
    assertThat(xml).contains(
        "<loc>https://www.fixy.com.uy/servicios/sanitario-plomero-ciudad-de-la-costa/</loc>");
    assertThat(xml).contains(
        "<loc>https://www.fixy.com.uy/servicios/barometrica-ciudad-de-la-costa/</loc>");
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

  // --- Fase 3: comercios con slug (gap analysis 2026-08-25 §3, punto 6) ---

  @Test
  void incluyeUnComercioActivoConSlugYSuLastmod() {
    Business business = persistBusiness("Comercio Sitemap Con Slug Test", "098777001");
    String slug = businessSlugService.ensureSlug(business);

    String xml = sitemapService.render();

    // Releer el updatedAt DESPUÉS de render(): ensureSlug hace un UPDATE que
    // Hibernate no flushea hasta la próxima query real (find-by-id no
    // dispara auto-flush) — recién la consulta de render() lo materializa.
    // Leer antes comparaba contra el updatedAt viejo (el del alta), no el
    // real post-slug (mismo motivo documentado para el caso de Offer abajo).
    Business reloaded = businessRepository.findById(business.getId()).orElseThrow();
    String expectedLastmod = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(reloaded.getUpdatedAt());

    assertThat(xml).contains("<loc>https://www.fixy.com.uy/comercio/" + slug + "</loc>");
    assertThat(xml).contains("<lastmod>" + expectedLastmod + "</lastmod>");
  }

  @Test
  void noIncluyeUnComercioActivoSinSlugTodavia() {
    Business business = persistBusiness("Comercio Sitemap Sin Slug Test", "098777002");
    // nunca se llama ensureSlug — comercio activo pero sin slug asignado.

    String xml = sitemapService.render();

    assertThat(xml).doesNotContain(business.getName());
  }

  @Test
  void noIncluyeUnComercioInactivoAunqueTengaSlug() {
    Business business = persistBusiness("Comercio Sitemap Inactivo Test", "098777003");
    String slug = businessSlugService.ensureSlug(business);
    business.setStatus(com.fixy.backend.model.BusinessStatus.INACTIVE);
    businessRepository.save(business);

    String xml = sitemapService.render();

    assertThat(xml).doesNotContain("<loc>https://www.fixy.com.uy/comercio/" + slug + "</loc>");
  }
}
