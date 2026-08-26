package com.fixy.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.OfferPublicResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Además de los OG se inyecta JSON-LD (schema.org Offer) en el head y un
 * resumen en HTML plano DENTRO de {@code <div id="root">}: los lectores de
 * asistentes de IA (verificado con Gemini, 2026-08-26) ignoran los meta tags
 * y solo extraen texto visible del body — con el root vacío reportan "la
 * página requiere JavaScript". El resumen lo ven únicamente los bots:
 * {@code createRoot().render()} reemplaza los hijos del root al montar la
 * SPA, así que un humano con JS ve la app de siempre.
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
      + "<link rel=\"canonical\" href=\"https://www.fixy.com.uy/\" />"
      + "<title>Fixy — Servicios del hogar en Ciudad de la Costa</title>"
      + "</head><body><div id=\"root\"></div></body></html>";

  private static final Pattern OG_META_TAG = Pattern.compile(
      "<meta[^>]*(?:property=\"og:[^\"]*\"|name=\"twitter:card\")[^>]*/?>\\s*",
      Pattern.CASE_INSENSITIVE);

  private static final Pattern TITLE_TAG = Pattern.compile(
      "<title>.*?</title>", Pattern.CASE_INSENSITIVE);

  /** Shell base tiene el canonical hardcodeado a la home (ver index.html del
   * frontend) — para /og/oferta/{id} se reescribe a la URL canónica de la
   * oferta, la misma que og:url, para que motores de búsqueda no indexen
   * todas las ofertas apuntando a "/". */
  private static final Pattern CANONICAL_LINK_TAG = Pattern.compile(
      "<link[^>]*rel=\"canonical\"[^>]*/?>", Pattern.CASE_INSENSITIVE);

  /** Root vacío del shell de Vite — el único punto donde es seguro inyectar
   * contenido visible: React lo reemplaza al montar. Si el shell algún día
   * cambia y el patrón no matchea, se omite el resumen (nunca arriesgar
   * contenido duplicado visible junto a la SPA). */
  private static final Pattern EMPTY_ROOT_DIV = Pattern.compile(
      "<div id=\"root\">\\s*</div>", Pattern.CASE_INSENSITIVE);

  private static final ZoneId MONTEVIDEO = ZoneId.of("America/Montevideo");
  private static final DateTimeFormatter FECHA_CORTA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final OfferService offerService;
  private final ObjectMapper objectMapper;
  private final Path indexPath;
  private final String publicAppBaseUrl;
  private final String publicApiBaseUrl;

  public OfferOgHtmlService(
      OfferService offerService,
      ObjectMapper objectMapper,
      @Value("${fixy.frontend.index-path:/var/www/fixy-app/current/index.html}") String indexPath,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl,
      @Value("${fixy.public-api-base-url:https://api.fixy.com.uy}") String publicApiBaseUrl
  ) {
    this.offerService = offerService;
    this.objectMapper = objectMapper;
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
    String html = injectOgTags(baseHtml, offer);
    html = insertBeforeHeadClose(html, buildJsonLdScript(offer));
    return injectBotReadableSummary(html, offer);
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

    String title = HtmlUtils.htmlEscape(buildTitle(offer));
    String description = HtmlUtils.htmlEscape(buildDescription(offer));
    String imageUrl = (offer.photoUrl() != null && !offer.photoUrl().isBlank())
        ? offer.photoUrl()
        : publicApiBaseUrl + "/images/og-default.png";
    String url = offerUrl(offer);

    String tags = "<meta property=\"og:type\" content=\"website\" />"
        + "<meta property=\"og:title\" content=\"" + title + "\" />"
        + "<meta property=\"og:description\" content=\"" + description + "\" />"
        + "<meta property=\"og:image\" content=\"" + imageUrl + "\" />"
        + "<meta property=\"og:image:width\" content=\"1200\" />"
        + "<meta property=\"og:image:height\" content=\"630\" />"
        + "<meta property=\"og:url\" content=\"" + url + "\" />"
        + "<meta name=\"twitter:card\" content=\"summary_large_image\" />";

    String withTags = insertBeforeHeadClose(withoutOldTags, tags);
    String withTitle = replaceTitleTag(withTags, title);
    return replaceCanonicalTag(withTitle, url);
  }

  private String offerUrl(OfferPublicResponse offer) {
    return publicAppBaseUrl + "/oferta/" + offer.id();
  }

  /**
   * JSON-LD schema.org {@code Offer} + {@code LocalBusiness} anidado. Los
   * campos opcionales (dirección, zona, geo, foto, vencimiento) solo se
   * emiten cuando existen — un JSON-LD con nulls es peor que uno corto.
   * La serialización escapa {@code <} como {@code \u003c}: el JSON vive
   * dentro de un {@code <script>}, y un título malicioso con
   * {@code </script>} cerraría el tag y ejecutaría lo que sigue.
   */
  private String buildJsonLdScript(OfferPublicResponse offer) {
    Map<String, Object> business = new LinkedHashMap<>();
    business.put("@type", "LocalBusiness");
    business.put("name", offer.businessName());
    if (offer.businessAddress() != null && !offer.businessAddress().isBlank()) {
      business.put("address", offer.businessAddress());
    }
    String zoneText = zoneText(offer);
    if (zoneText != null) {
      business.put("areaServed", zoneText);
    }
    if (offer.businessLatitude() != null && offer.businessLongitude() != null) {
      Map<String, Object> geo = new LinkedHashMap<>();
      geo.put("@type", "GeoCoordinates");
      geo.put("latitude", offer.businessLatitude());
      geo.put("longitude", offer.businessLongitude());
      business.put("geo", geo);
    }

    Map<String, Object> jsonLd = new LinkedHashMap<>();
    jsonLd.put("@context", "https://schema.org");
    jsonLd.put("@type", "Offer");
    jsonLd.put("name", buildTitle(offer));
    jsonLd.put("description", buildDescription(offer));
    jsonLd.put("url", offerUrl(offer));
    if (offer.photoUrl() != null && !offer.photoUrl().isBlank()) {
      jsonLd.put("image", offer.photoUrl());
    }
    if (offer.validUntil() != null) {
      jsonLd.put("validThrough", offer.validUntil().toString());
    }
    jsonLd.put("offeredBy", business);

    try {
      String json = objectMapper.writeValueAsString(jsonLd).replace("<", "\\u003c");
      return "<script type=\"application/ld+json\">" + json + "</script>";
    } catch (JsonProcessingException e) {
      log.warn("no se pudo serializar el JSON-LD de la oferta {}, se omite", offer.id(), e);
      return "";
    }
  }

  /**
   * Resumen en HTML plano dentro del root vacío: es lo único que ven los
   * lectores sin JS (asistentes de IA, buscadores sin render). Repite el
   * contrato de título/descripción de los OG y agrega lo que el vecino
   * preguntaría (detalle, dirección, vigencia) más el link canónico.
   */
  private String injectBotReadableSummary(String html, OfferPublicResponse offer) {
    Matcher matcher = EMPTY_ROOT_DIV.matcher(html);
    if (!matcher.find()) {
      return html;
    }
    StringBuilder block = new StringBuilder("<main>");
    block.append("<h1>").append(HtmlUtils.htmlEscape(buildTitle(offer))).append("</h1>");
    block.append("<p>").append(HtmlUtils.htmlEscape(buildDescription(offer))).append("</p>");
    if (offer.description() != null && !offer.description().isBlank()) {
      block.append("<p>").append(HtmlUtils.htmlEscape(offer.description())).append("</p>");
    }
    if (offer.businessAddress() != null && !offer.businessAddress().isBlank()) {
      block.append("<p>Dirección: ").append(HtmlUtils.htmlEscape(offer.businessAddress())).append("</p>");
    }
    if (offer.validUntil() != null) {
      block.append("<p>Oferta válida hasta el ")
          .append(FECHA_CORTA.format(offer.validUntil().atZoneSameInstant(MONTEVIDEO)))
          .append("</p>");
    }
    block.append("<p><a href=\"").append(HtmlUtils.htmlEscape(offerUrl(offer)))
        .append("\">Ver esta oferta en Fixy</a></p>");
    block.append("</main>");
    return html.substring(0, matcher.start())
        + "<div id=\"root\">" + block + "</div>"
        + html.substring(matcher.end());
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
   * Reescribe {@code <link rel="canonical">} a la URL canónica de la oferta
   * (misma que og:url). Si el shell no trae el tag (caso del fallback en
   * dev/test cuando no hay index.html real en disco todavía sin canonical),
   * no inserta uno nuevo — nada roto, solo no hay tag que reescribir.
   */
  private String replaceCanonicalTag(String html, String url) {
    Matcher matcher = CANONICAL_LINK_TAG.matcher(html);
    if (!matcher.find()) {
      return html;
    }
    String escapedUrl = HtmlUtils.htmlEscape(url);
    return html.substring(0, matcher.start())
        + "<link rel=\"canonical\" href=\"" + escapedUrl + "\" />"
        + html.substring(matcher.end());
  }

  /**
   * Algoritmo de armado (contrato exacto, no reinterpretar): título +
   * descuento, unidos con " · " — un link compartido tiene que anunciar el
   * beneficio en el título mismo (lo que WhatsApp muestra más grande),
   * no solo en la descripción de abajo. Si no hay {@code discountText},
   * o el título ya lo contiene (caso común: "2x1 en muzzarella" con
   * descuento "2x1" — sumaría "… · 2x1" redundante), el título queda tal
   * cual — no hay nada que agregar.
   */
  private String buildTitle(OfferPublicResponse offer) {
    String discount = offer.discountText();
    if (discount != null
        && !discount.isBlank()
        && !offer.title().toLowerCase().contains(discount.toLowerCase())) {
      return offer.title() + " · " + discount;
    }
    return offer.title();
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
