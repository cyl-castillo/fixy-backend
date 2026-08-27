package com.fixy.backend.service;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Arma el {@code sitemap.xml} servido en {@code GET /sitemap.xml} (ver
 * {@code SitemapController}) — SEO básico: home, {@code /ofertas},
 * {@code /sumate} (puerta única de registro) y una entrada por cada oferta
 * pública vigente. Mismo criterio {@code ACTIVE} +
 * {@code validUntil} no vencida que {@code OfferService.listPublic}, para
 * que un buscador nunca indexe una oferta que el propio backend ya no le
 * serviría a un vecino (misma consulta: {@link OfferRepository#findByStatusAndValidUntilAfter}).
 *
 * <p>Sin caché en memoria: a este volumen (decenas de ofertas activas)
 * armar el XML por request es más simple que invalidar un caché a mano, y
 * nginx tampoco cachea esta ruta en prod (contenido dinámico, ver
 * {@code deploy/aws/nginx-www.fixy.com.uy.conf}).
 */
@Service
public class SitemapService {

  private static final DateTimeFormatter LASTMOD_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  private final OfferRepository offerRepository;
  private final BusinessRepository businessRepository;
  private final Clock clock;
  private final String publicAppBaseUrl;

  public SitemapService(
      OfferRepository offerRepository,
      BusinessRepository businessRepository,
      Clock clock,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl
  ) {
    this.offerRepository = offerRepository;
    this.businessRepository = businessRepository;
    this.clock = clock;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
  }

  /** Arma el XML completo. Nunca lanza — sin ofertas/comercios, igual devuelve home + /ofertas. */
  public String render() {
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<Offer> vigentes = offerRepository.findByStatusAndValidUntilAfter(OfferStatus.ACTIVE, now);
    // Un comercio entra recién cuando YA tiene slug — el sitemap nunca fuerza
    // BusinessSlugService.ensureSlug (mutar en un GET de sitemap no, ver gap
    // analysis §6): los existentes lo ganan cuando ops pide el link público.
    List<Business> businessesConSlug = businessRepository.findByStatusAndSlugIsNotNull(BusinessStatus.ACTIVE);

    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    appendUrl(xml, publicAppBaseUrl + "/", null);
    appendUrl(xml, publicAppBaseUrl + "/ofertas", null);
    appendUrl(xml, publicAppBaseUrl + "/sumate", null);
    for (Offer offer : vigentes) {
      appendUrl(xml, publicAppBaseUrl + "/oferta/" + offer.getId(), offer.getUpdatedAt());
    }
    for (Business business : businessesConSlug) {
      appendUrl(xml, publicAppBaseUrl + "/comercio/" + business.getSlug(), business.getUpdatedAt());
    }
    xml.append("</urlset>\n");
    return xml.toString();
  }

  private void appendUrl(StringBuilder xml, String loc, OffsetDateTime lastmod) {
    xml.append("  <url>\n");
    xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
    if (lastmod != null) {
      xml.append("    <lastmod>").append(LASTMOD_FORMAT.format(lastmod)).append("</lastmod>\n");
    }
    xml.append("  </url>\n");
  }

  /**
   * Escape mínimo de los cinco caracteres reservados de XML. Hoy ningún
   * valor que pasa por acá es texto libre de usuario (las URLs se arman
   * enteramente del lado del backend: base pública + id numérico), pero
   * este es el único punto de entrada de texto al documento y el XML se
   * arma a mano sin librería — se escapa igual, defensivo y barato.
   */
  private String escapeXml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
