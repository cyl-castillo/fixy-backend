package com.fixy.backend.service;

import com.fixy.backend.dto.OfferPublicResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/**
 * Arma el HTML que se sirve en {@code GET /og/oferta/{id}} (ver
 * {@code OfferOgController}): WhatsApp/Meta no ejecutan JS, así que un link
 * compartido de {@code /oferta/{id}} necesita meta tags Open Graph con datos
 * reales ya presentes en el HTML de la primera respuesta. La SPA sigue
 * siendo la que renderiza para humanos — esto solo le agrega cabecera a lo
 * que ya existe (index.html real del release activo del frontend).
 *
 * <p>Si la oferta no existe o no está vigente, se sirve el shell tal cual
 * salió del disco, con sus OG genéricos de Fixy intactos — la SPA ya
 * resuelve "ya no vigente" del lado cliente.
 */
@Service
public class OfferOgHtmlService {

  private static final Logger log = LoggerFactory.getLogger(OfferOgHtmlService.class);

  /**
   * Fallback embebido para cuando {@code fixy.frontend.index-path} no existe
   * en disco (caso normal en dev/test — ahí no hay un release de
   * {@code fixy-app} desplegado). En prod SIEMPRE se usa el index.html real.
   */
  private static final String FALLBACK_HTML = "<!doctype html><html lang=\"es\"><head>"
      + "<meta charset=\"UTF-8\" />"
      + "<title>Fixy — Servicios del hogar en Ciudad de la Costa</title>"
      + "</head><body><div id=\"root\"></div></body></html>";

  private static final Pattern OG_META_TAG = Pattern.compile(
      "<meta[^>]*(?:property=\"og:[^\"]*\"|name=\"twitter:card\")[^>]*/?>\\s*",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern TITLE_TAG = Pattern.compile(
      "<title>.*?</title>", Pattern.CASE_INSENSITIVE);

  private final OfferService offerService;
  private final Path indexPath;
  private final String publicAppBaseUrl;
  private final String publicApiBaseUrl;

  public OfferOgHtmlService(
      OfferService offerService,
      @Value("${fixy.frontend.index-path:/var/www/fixy-app/current/index.html}") String indexPath,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl,
      @Value("${fixy.public-api-base-url:https://api.fixy.com.uy}") String publicApiBaseUrl
  ) {
    this.offerService = offerService;
    this.indexPath = Path.of(indexPath);
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
    this.publicApiBaseUrl = publicApiBaseUrl.replaceAll("/+$", "");
  }

  /** SIEMPRE devuelve HTML servible — nunca lanza, nunca 404 (contrato del endpoint). */
  public String render(Long id) {
    String baseHtml = readIndexHtml();
    OfferPublicResponse offer;
    try {
      offer = offerService.getPublic(id);
    } catch (ResponseStatusException notFoundOrExpired) {
      return baseHtml;
    }
    return injectOgTags(baseHtml, offer);
  }

  private String readIndexHtml() {
    try {
      if (Files.isRegularFile(indexPath)) {
        return Files.readString(indexPath);
      }
    } catch (IOException e) {
      log.warn("no se pudo leer el index.html del frontend en {}, uso el fallback embebido", indexPath, e);
    }
    return FALLBACK_HTML;
  }

  private String injectOgTags(String html, OfferPublicResponse offer) {
    String withoutOldTags = OG_META_TAG.matcher(html).replaceAll("");

    String title = HtmlUtils.htmlEscape(offer.title());
    String description = HtmlUtils.htmlEscape(buildDescription(offer));
    String imageUrl = (offer.photoUrl() != null && !offer.photoUrl().isBlank())
        ? offer.photoUrl()
        : publicApiBaseUrl + "/images/og-default.png";
    String url = publicAppBaseUrl + "/oferta/" + offer.id();

    String tags = "<meta property=\"og:type\" content=\"website\" />"
        + "<meta property=\"og:title\" content=\"" + title + "\" />"
        + "<meta property=\"og:description\" content=\"" + description + "\" />"
        + "<meta property=\"og:image\" content=\"" + imageUrl + "\" />"
        + "<meta property=\"og:image:width\" content=\"1200\" />"
        + "<meta property=\"og:image:height\" content=\"630\" />"
        + "<meta property=\"og:url\" content=\"" + url + "\" />"
        + "<meta name=\"twitter:card\" content=\"summary_large_image\" />";

    String withTags = insertBeforeHeadClose(withoutOldTags, tags);
    return replaceTitleTag(withTags, title);
  }

  private String insertBeforeHeadClose(String html, String tags) {
    int headEnd = html.indexOf("</head>");
    if (headEnd < 0) {
      return html + tags;
    }
    return html.substring(0, headEnd) + tags + html.substring(headEnd);
  }

  private String replaceTitleTag(String html, String escapedTitle) {
    Matcher matcher = TITLE_TAG.matcher(html);
    if (!matcher.find()) {
      return html;
    }
    return html.substring(0, matcher.start()) + "<title>" + escapedTitle + "</title>" + html.substring(matcher.end());
  }

  /**
   * Algoritmo de armado (contrato exacto, no reinterpretar): partes =
   * [discountText si no es blank, businessName siempre, zonaText] donde
   * zonaText = zone si no es blank, si no "Todas las zonas" si allZones,
   * si no se omite. Unidas con " · ". Si sourceName no es blank, se agrega
   * al final " · Fuente: {sourceName}".
   */
  private String buildDescription(OfferPublicResponse offer) {
    List<String> parts = new ArrayList<>();
    if (offer.discountText() != null && !offer.discountText().isBlank()) {
      parts.add(offer.discountText());
    }
    parts.add(offer.businessName());
    String zoneText = zoneText(offer);
    if (zoneText != null) {
      parts.add(zoneText);
    }

    String description = String.join(" · ", parts);
    if (offer.sourceName() != null && !offer.sourceName().isBlank()) {
      description = description + " · Fuente: " + offer.sourceName();
    }
    return description;
  }

  private String zoneText(OfferPublicResponse offer) {
    if (offer.zone() != null && !offer.zone().isBlank()) {
      return offer.zone();
    }
    if (offer.allZones()) {
      return "Todas las zonas";
    }
    return null;
  }
}
