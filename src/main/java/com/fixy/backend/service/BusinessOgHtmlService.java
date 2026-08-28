package com.fixy.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessHour;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessHourRepository;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
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
import org.springframework.web.util.HtmlUtils;

/**
 * Arma el HTML que se sirve en {@code GET /og/comercio/{slug}} (ver {@code
 * BusinessOgController}) — réplica exacta del patrón de {@link
 * OfferOgHtmlService} para la página pública del comercio (Fase 3, gap
 * analysis 2026-08-25 §3), con un agregado: un {@code <script
 * type="application/ld+json">} con {@code LocalBusiness} para que Google
 * entienda horarios/dirección/ofertas del comercio sin ejecutar JS.
 *
 * <p>Deliberadamente NO reusa {@code PublicBusinessService}: ese servicio
 * tiene rate-limit por IP e incrementa {@code view_count} como efecto de
 * lectura — ninguno de los dos es aceptable acá (el contrato es SIEMPRE
 * 200, y un bot de preview no debería poder recortarle cupo de rate-limit a
 * un vecino real, ni cada preview debería inflar el contador de vistas).
 * Consulta los repositorios directo, solo lectura, sin efectos secundarios.
 *
 * <p>Si el comercio no existe, no está {@code ACTIVE} o no tiene slug
 * todavía, se sirve el shell tal cual salió del disco, con sus OG genéricos
 * de Fixy intactos y SIN JSON-LD de negocio.
 */
@Service
public class BusinessOgHtmlService {

  private static final Logger log = LoggerFactory.getLogger(BusinessOgHtmlService.class);

  /** Mismo fallback embebido que {@link OfferOgHtmlService} — dev/test no tiene un release real de fixy-app en disco. */
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

  private static final Pattern CANONICAL_LINK_TAG = Pattern.compile(
      "<link[^>]*rel=\"canonical\"[^>]*/?>", Pattern.CASE_INSENSITIVE);

  /** Nombres de día en inglés, vocabulario schema.org — dayOfWeek ISO (1=lunes..7=domingo), índice 0-based acá. */
  private static final String[] SCHEMA_DAY_NAMES = {
      "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
  };

  private final BusinessRepository businessRepository;
  private final BusinessHourRepository businessHourRepository;
  private final OfferRepository offerRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Path indexPath;
  private final String publicAppBaseUrl;
  private final String publicApiBaseUrl;

  public BusinessOgHtmlService(
      BusinessRepository businessRepository,
      BusinessHourRepository businessHourRepository,
      OfferRepository offerRepository,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${fixy.frontend.index-path:/var/www/fixy-app/current/index.html}") String indexPath,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl,
      @Value("${fixy.public-api-base-url:https://api.fixy.com.uy}") String publicApiBaseUrl
  ) {
    this.businessRepository = businessRepository;
    this.businessHourRepository = businessHourRepository;
    this.offerRepository = offerRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.indexPath = Path.of(indexPath);
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
    this.publicApiBaseUrl = publicApiBaseUrl.replaceAll("/+$", "");
  }

  /** SIEMPRE devuelve HTML servible — nunca lanza, nunca 404 (contrato del endpoint). */
  public String render(String slug) {
    String baseHtml = readIndexHtml();
    if (slug == null || slug.isBlank()) {
      return baseHtml;
    }
    Business business = businessRepository.findBySlug(slug).orElse(null);
    if (business == null || business.getStatus() != BusinessStatus.ACTIVE) {
      return baseHtml;
    }
    return injectTags(baseHtml, business);
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

  private String injectTags(String html, Business business) {
    List<BusinessHour> hours = businessHourRepository.findByBusinessIdOrderByDayOfWeekAscOpensAtAsc(business.getId());
    OffsetDateTime now = OffsetDateTime.now(clock);
    List<Offer> vigentOffers = offerRepository.findByBusinessIdOrderByCreatedAtDesc(business.getId()).stream()
        .filter(offer -> offer.getStatus() == OfferStatus.ACTIVE
            && offer.getValidUntil() != null && offer.getValidUntil().isAfter(now))
        .toList();

    String url = publicAppBaseUrl + "/comercio/" + business.getSlug();
    String title = HtmlUtils.htmlEscape(buildTitle(business));
    String description = HtmlUtils.htmlEscape(buildDescription(business));
    String imageUrl = publicApiBaseUrl + "/images/og-default.png";

    String withoutOldTags = OG_META_TAG.matcher(html).replaceAll("");
    String tags = "<meta property=\"og:type\" content=\"business.business\" />"
        + "<meta property=\"og:title\" content=\"" + title + "\" />"
        + "<meta property=\"og:description\" content=\"" + description + "\" />"
        + "<meta property=\"og:image\" content=\"" + imageUrl + "\" />"
        + "<meta property=\"og:image:width\" content=\"1200\" />"
        + "<meta property=\"og:image:height\" content=\"630\" />"
        + "<meta property=\"og:url\" content=\"" + url + "\" />"
        + "<meta name=\"twitter:card\" content=\"summary_large_image\" />";

    String withTags = insertBeforeHeadClose(withoutOldTags, tags);
    String withJsonLd = insertJsonLd(withTags, business, hours, vigentOffers, url);
    String withTitle = replaceTitleTag(withJsonLd, title);
    return replaceCanonicalTag(withTitle, url);
  }

  private String insertBeforeHeadClose(String html, String tags) {
    int headEnd = html.indexOf("</head>");
    if (headEnd < 0) {
      return html + tags;
    }
    return html.substring(0, headEnd) + tags + html.substring(headEnd);
  }

  private String insertJsonLd(
      String html, Business business, List<BusinessHour> hours, List<Offer> offers, String canonicalUrl
  ) {
    String json;
    try {
      json = objectMapper.writeValueAsString(buildLocalBusiness(business, hours, offers, canonicalUrl));
    } catch (JsonProcessingException e) {
      log.warn("no se pudo serializar el JSON-LD de LocalBusiness para el comercio {}", business.getId(), e);
      return html;
    }
    // "</" dentro del JSON cerraría el <script> prematuramente si apareciera
    // (ej. una descripción con una URL) — escape defensivo estándar.
    String safeJson = json.replace("</", "<\\/");
    String tag = "<script type=\"application/ld+json\">" + safeJson + "</script>";
    return insertBeforeHeadClose(html, tag);
  }

  /**
   * Contrato exacto (gap analysis 2026-08-25 §3, punto 4): {@code @type
   * LocalBusiness}, {@code name}, {@code description}, {@code address}
   * (PostalAddress con {@code streetAddress} y {@code addressLocality} =
   * zona, {@code addressCountry} UY), {@code geo} (GeoCoordinates si hay
   * lat/lng), {@code openingHoursSpecification} desde {@code business_hours}
   * (dayOfWeek en vocabulario schema.org, {@code opens}/{@code closes}),
   * {@code areaServed}, {@code url} canónica, y {@code makesOffer} (array de
   * {@code Offer} con {@code name}/{@code validThrough}) de sus ofertas
   * vigentes.
   */
  private Map<String, Object> buildLocalBusiness(
      Business business, List<BusinessHour> hours, List<Offer> offers, String canonicalUrl
  ) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("@context", "https://schema.org");
    json.put("@type", "LocalBusiness");
    json.put("name", business.getName());
    if (hasText(business.getDescription())) {
      json.put("description", business.getDescription());
    }

    Map<String, Object> address = new LinkedHashMap<>();
    address.put("@type", "PostalAddress");
    if (hasText(business.getAddress())) {
      address.put("streetAddress", business.getAddress());
    }
    if (hasText(business.getPrimaryZone())) {
      address.put("addressLocality", business.getPrimaryZone());
    }
    address.put("addressCountry", "UY");
    json.put("address", address);

    if (business.getLatitude() != null && business.getLongitude() != null) {
      Map<String, Object> geo = new LinkedHashMap<>();
      geo.put("@type", "GeoCoordinates");
      geo.put("latitude", business.getLatitude());
      geo.put("longitude", business.getLongitude());
      json.put("geo", geo);
    }

    if (!hours.isEmpty()) {
      List<Map<String, Object>> openingHours = new ArrayList<>();
      for (BusinessHour hour : hours) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("@type", "OpeningHoursSpecification");
        spec.put("dayOfWeek", SCHEMA_DAY_NAMES[hour.getDayOfWeek() - 1]);
        spec.put("opens", hour.getOpensAt());
        spec.put("closes", hour.getClosesAt());
        openingHours.add(spec);
      }
      json.put("openingHoursSpecification", openingHours);
    }

    if (hasText(business.getPrimaryZone())) {
      json.put("areaServed", business.getPrimaryZone());
    }

    json.put("url", canonicalUrl);

    if (!offers.isEmpty()) {
      List<Map<String, Object>> makesOffer = new ArrayList<>();
      for (Offer offer : offers) {
        Map<String, Object> offerJson = new LinkedHashMap<>();
        offerJson.put("@type", "Offer");
        offerJson.put("name", offer.getTitle());
        if (offer.getValidUntil() != null) {
          offerJson.put("validThrough", offer.getValidUntil().toString());
        }
        makesOffer.add(offerJson);
      }
      json.put("makesOffer", makesOffer);
    }

    return json;
  }

  private String replaceTitleTag(String html, String escapedTitle) {
    Matcher matcher = TITLE_TAG.matcher(html);
    if (!matcher.find()) {
      return html;
    }
    return html.substring(0, matcher.start()) + "<title>" + escapedTitle + "</title>" + html.substring(matcher.end());
  }

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
   * "{nombre} — {rubro} en {zona} | Fixy", omitiendo las partes que falten:
   * sin zona "{nombre} — {rubro} | Fixy", sin rubro "{nombre} en {zona} |
   * Fixy", sin ninguna "{nombre} | Fixy". El rubro "otro" cuenta como
   * ausente: casi todos los comercios de la ingesta de beneficios lo tienen
   * como placeholder y "TaTa — otro | Fixy" queda mal en resultados de
   * búsqueda (detectado en el backfill de slugs 2026-08-26).
   */
  private String buildTitle(Business business) {
    StringBuilder title = new StringBuilder(business.getName());
    String rubro = displayCategory(business);
    if (rubro != null) {
      title.append(" — ").append(rubro);
    }
    if (hasText(business.getPrimaryZone())) {
      title.append(" en ").append(business.getPrimaryZone());
    }
    title.append(" | Fixy");
    return title.toString();
  }

  /** description del comercio si existe, si no rubro + zona — mismo espíritu que OfferOgHtmlService.buildDescription. */
  private String buildDescription(Business business) {
    if (hasText(business.getDescription())) {
      return business.getDescription();
    }
    List<String> parts = new ArrayList<>();
    String rubro = displayCategory(business);
    if (rubro != null) {
      parts.add(rubro);
    }
    if (hasText(business.getPrimaryZone())) {
      parts.add(business.getPrimaryZone());
    }
    if (parts.isEmpty()) {
      return "Comercio del barrio en Fixy";
    }
    return String.join(" · ", parts);
  }

  /** Rubro mostrable, o null si falta o es el placeholder "otro". */
  private String displayCategory(Business business) {
    String category = business.getCategory();
    if (!hasText(category) || "otro".equalsIgnoreCase(category.trim())) {
      return null;
    }
    return category;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
