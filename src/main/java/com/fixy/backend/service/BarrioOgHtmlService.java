package com.fixy.backend.service;

import com.fixy.backend.dto.BarrioDtos.PublicReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/**
 * HTML para {@code GET /og/barrio/r/{id}} (nginx lo usa para {@code /barrio/r/{id}}): los bots de
 * WhatsApp/Facebook no ejecutan JS, así que el shell de la SPA sale con título, descripción e
 * {@code og:image} reales del reclamo. Mismo patrón que {@link OfferOgHtmlService}. SIEMPRE
 * devuelve HTML servible: si el reclamo no existe o no es público, el shell genérico intacto.
 * Solo usa datos públicos (nunca descripción ni teléfono).
 */
@Service
public class BarrioOgHtmlService {

  private static final Logger log = LoggerFactory.getLogger(BarrioOgHtmlService.class);

  private static final String FALLBACK_HTML = "<!doctype html><html lang=\"es\"><head>"
      + "<meta charset=\"UTF-8\" />"
      + "<link rel=\"canonical\" href=\"https://www.fixy.com.uy/\" />"
      + "<title>Fixy — Servicios del hogar en Ciudad de la Costa</title>"
      + "</head><body><div id=\"root\"></div></body></html>";
  private static final Pattern OG_META_TAG = Pattern.compile(
      "<meta[^>]*(?:property=\"og:[^\"]*\"|name=\"twitter:card\")[^>]*/?>\\s*", Pattern.CASE_INSENSITIVE);
  private static final Pattern TITLE_TAG = Pattern.compile("<title>.*?</title>", Pattern.CASE_INSENSITIVE);
  private static final Pattern CANONICAL_LINK_TAG = Pattern.compile(
      "<link[^>]*rel=\"canonical\"[^>]*/?>", Pattern.CASE_INSENSITIVE);
  private static final Pattern EMPTY_ROOT_DIV = Pattern.compile(
      "<div id=\"root\">\\s*</div>", Pattern.CASE_INSENSITIVE);

  private final BarrioService barrio;
  private final Path indexPath;
  private final String publicAppBaseUrl;
  private final String publicApiBaseUrl;

  public BarrioOgHtmlService(
      BarrioService barrio,
      @Value("${fixy.frontend.index-path:/var/www/fixy-app/current/index.html}") String indexPath,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl,
      @Value("${fixy.public-api-base-url:https://api.fixy.com.uy}") String publicApiBaseUrl
  ) {
    this.barrio = barrio;
    this.indexPath = Path.of(indexPath);
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
    this.publicApiBaseUrl = publicApiBaseUrl.replaceAll("/+$", "");
  }

  public String render(Long id) {
    String baseHtml = readIndexHtml();
    PublicReport report;
    try {
      report = barrio.getPublic(id);
    } catch (ResponseStatusException notPublic) {
      return baseHtml;
    }
    String title = buildTitle(report);
    String description = buildDescription(report);
    String url = publicAppBaseUrl + "/barrio/r/" + report.id();
    String image = absoluteImage(report.photoUrl());

    String html = OG_META_TAG.matcher(baseHtml).replaceAll("");
    String tags = "<meta property=\"og:type\" content=\"website\" />"
        + "<meta property=\"og:title\" content=\"" + esc(title) + "\" />"
        + "<meta property=\"og:description\" content=\"" + esc(description) + "\" />"
        + "<meta property=\"og:image\" content=\"" + esc(image) + "\" />"
        + "<meta property=\"og:url\" content=\"" + esc(url) + "\" />"
        + "<meta name=\"twitter:card\" content=\"summary_large_image\" />";
    html = insertBeforeHeadClose(html, tags);
    html = replaceTitle(html, esc(title));
    html = replaceCanonical(html, url);
    return injectBotSummary(html, title, description, url);
  }

  static String buildTitle(PublicReport r) {
    String what = r.categoryLabel() + " en " + r.zone();
    if ("RESOLVED".equals(r.status())) {
      Integer days = r.daysToResolve();
      return what + " — resuelto" + (days == null ? "" : " en " + days + (days == 1 ? " día" : " días"));
    }
    return what + " — lleva " + r.daysOpen() + (r.daysOpen() == 1 ? " día" : " días");
  }

  static String buildDescription(PublicReport r) {
    String base = "RESOLVED".equals(r.status())
        ? "Los vecinos lo reportaron y ya está arreglado."
        : "Reclamo vecinal en el mapa del barrio.";
    String supporters = r.supporters() > 1 ? " " + r.supporters() + " vecinos lo reportaron." : "";
    return base + supporters + " Mirá el mapa del barrio en Fixy.";
  }

  /** Escapa solo lo significativo para HTML (&, <, >, comillas): los acentos y la raya van como UTF-8 literal. */
  private static String esc(String value) {
    return HtmlUtils.htmlEscape(value, "UTF-8");
  }

  private String absoluteImage(String photoUrl) {
    if (photoUrl == null || photoUrl.isBlank()) return publicApiBaseUrl + "/images/og-default.png";
    return photoUrl.startsWith("/") ? publicApiBaseUrl + photoUrl : photoUrl;
  }

  private String readIndexHtml() {
    try {
      if (Files.isRegularFile(indexPath)) return Files.readString(indexPath);
    } catch (IOException e) {
      log.warn("no se pudo leer el index.html del frontend en {}, uso el fallback embebido", indexPath, e);
    }
    return FALLBACK_HTML;
  }

  private String injectBotSummary(String html, String title, String description, String url) {
    Matcher m = EMPTY_ROOT_DIV.matcher(html);
    if (!m.find()) return html;
    String block = "<main><h1>" + esc(title) + "</h1><p>" + esc(description)
        + "</p><p><a href=\"" + esc(url) + "\">Ver este reclamo en Fixy</a></p></main>";
    return html.substring(0, m.start()) + "<div id=\"root\">" + block + "</div>" + html.substring(m.end());
  }

  private String insertBeforeHeadClose(String html, String tags) {
    int headEnd = html.indexOf("</head>");
    return headEnd < 0 ? html + tags : html.substring(0, headEnd) + tags + html.substring(headEnd);
  }

  private String replaceTitle(String html, String escapedTitle) {
    Matcher m = TITLE_TAG.matcher(html);
    if (!m.find()) return html;
    return html.substring(0, m.start()) + "<title>" + escapedTitle + "</title>" + html.substring(m.end());
  }

  private String replaceCanonical(String html, String url) {
    Matcher m = CANONICAL_LINK_TAG.matcher(html);
    if (!m.find()) return html;
    return html.substring(0, m.start()) + "<link rel=\"canonical\" href=\"" + esc(url) + "\" />"
        + html.substring(m.end());
  }
}
