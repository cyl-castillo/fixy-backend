package com.fixy.backend.service;

import com.fixy.backend.model.Offer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Motor de ranking de "conveniencia" de ofertas (fase 1, roadmap Ofertas —
 * Carlos aprobó reemplazar el orden cronológico por este score). Lógica
 * PURA y determinista: nada de {@code OffsetDateTime.now()} interno ni
 * acceso a red/DB — el "ahora" siempre llega por parámetro, así todo el
 * ranking es 100% testeable sin mockear tiempo ni levantar Spring.
 *
 * <p>El score es la suma de 6 señales independientes (no excluyentes entre
 * sí — una oferta puede sumar varias a la vez):
 * <ul>
 *   <li><b>Urgencia</b> (usa {@code validUntil}): vence dentro de 48h →
 *   +30 ("última chance"); si no, vence dentro de 7 días → +15; si no, +0.
 *   Sin {@code validUntil}, o ya vencida, → +0 (defensivo — en régimen
 *   normal toda oferta ACTIVE tiene validUntil futuro, ver
 *   {@code OfferService.approve}, y {@code listPublic} ya filtra vencidas
 *   antes de rankear).</li>
 *   <li><b>Descuento</b> (parsea {@code discountText} con regex
 *   determinista, sin heurística de lenguaje natural — señales
 *   acumulables, no mutuamente excluyentes): "NN%" → suma
 *   {@code min(NN, 70) * 0.5} puntos (tope anti-outlier: un "90% off" mal
 *   cargado no debe pisar todo el ranking); "2x1" → +25; contiene
 *   "gratis"/"free" (case-insensitive) → +20. Un texto como "2x1 + envío
 *   gratis" suma ambas señales. Sin nada parseable → +0.</li>
 *   <li><b>Calidad</b>: tiene {@code photoUrl} → +15; {@code description}
 *   con 60 caracteres o más → +10.</li>
 *   <li><b>Barrio primero</b> (misión de Fixy — se construye para el
 *   barrio, no para venderse): origen {@link Offer#ORIGIN_MANUAL} o
 *   {@link Offer#ORIGIN_WHATSAPP_FORWARD} (comercio o proveedor local real,
 *   cargado por ops o vía WhatsApp) → +25; {@link Offer#ORIGIN_SCRAPED_SOURCE}
 *   (bancos y otras fuentes públicas curadas) → +0. Esta señal es la que
 *   garantiza que, a paridad del resto, una oferta local le gane a una
 *   scrapeada.</li>
 *   <li><b>Frescura</b>: {@code createdAt} de menos de 72h → +10.</li>
 *   <li><b>Interacción</b> (fase 3 — señal real del barrio, no autodeclarada
 *   por quien carga la oferta): {@code min(25, viewCount * 0.2 + inquiryCount
 *   * 5.0 + likeCount * 2.0)}. El tope de +25 es deliberado y va al mismo
 *   nivel que el bonus de Barrio primero (+25): evita el feedback loop
 *   "la más vista se vuelve más vista" (rico se hace más rico) y garantiza
 *   que la popularidad ayude pero nunca aplaste a una oferta local recién
 *   cargada sin historial de interacción — sigue pudiendo ganarle por
 *   urgencia, descuento, calidad o barrio. {@code viewCount} y
 *   {@code likeCount} viven en la propia {@link Offer}; {@code inquiryCount}
 *   no es columna (se cuenta contra {@code OfferInquiry}) y llega por
 *   parámetro — {@code 0} si el caller no lo tiene (overloads de
 *   {@link #score}, {@link #rank} y {@link #comparator} sin ese parámetro).</li>
 * </ul>
 *
 * <p>Desempate estable (orden total — nunca hay dos ofertas "empatadas" sin
 * criterio, salvo el caso imposible de ids duplicados): score desc →
 * createdAt desc → id desc.
 */
@Service
public class OfferRankingService {

  static final double URGENCY_WITHIN_48H = 30;
  static final double URGENCY_WITHIN_7D = 15;

  static final double DISCOUNT_PERCENT_WEIGHT = 0.5;
  static final double DISCOUNT_PERCENT_CAP = 70;
  static final double DISCOUNT_2X1 = 25;
  static final double DISCOUNT_FREE = 20;

  static final double QUALITY_PHOTO = 15;
  static final double QUALITY_DESCRIPTION = 10;
  static final int QUALITY_DESCRIPTION_MIN_LENGTH = 60;

  static final double ORIGIN_LOCAL = 25;

  static final double FRESHNESS_BONUS = 10;
  static final long FRESHNESS_WINDOW_HOURS = 72;

  static final double INTERACTION_VIEW_WEIGHT = 0.2;
  static final double INTERACTION_INQUIRY_WEIGHT = 5.0;
  static final double INTERACTION_LIKE_WEIGHT = 2.0;
  static final double INTERACTION_CAP = 25.0;

  private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3})\\s*%");
  private static final Pattern TWO_FOR_ONE_PATTERN = Pattern.compile("2\\s*x\\s*1", Pattern.CASE_INSENSITIVE);
  private static final Pattern FREE_PATTERN = Pattern.compile("gratis|free", Pattern.CASE_INSENSITIVE);

  /** Copia de {@code offers} en orden de conveniencia, sin señal de interacción (inquiryCount = 0 para todas). */
  public List<Offer> rank(List<Offer> offers, OffsetDateTime now) {
    return rank(offers, now, Map.of());
  }

  /**
   * Copia de {@code offers} en orden de conveniencia (ver javadoc de clase),
   * incluida la señal de interacción. No muta la entrada.
   *
   * @param inquiryCountsByOfferId inquiryCount de cada oferta ({@code
   * OfferId -> count}); una oferta ausente del mapa cuenta como 0.
   */
  public List<Offer> rank(List<Offer> offers, OffsetDateTime now, Map<Long, Integer> inquiryCountsByOfferId) {
    return offers.stream().sorted(comparator(now, inquiryCountsByOfferId)).toList();
  }

  /** Comparador reutilizable, sin señal de interacción — ver overload con {@code inquiryCountsByOfferId}. */
  public Comparator<Offer> comparator(OffsetDateTime now) {
    return comparator(now, Map.of());
  }

  /** Comparador reutilizable: score desc → createdAt desc → id desc. */
  public Comparator<Offer> comparator(OffsetDateTime now, Map<Long, Integer> inquiryCountsByOfferId) {
    return Comparator
        .comparingDouble((Offer offer) ->
            score(offer, now, inquiryCountsByOfferId.getOrDefault(offer.getId(), 0))).reversed()
        .thenComparing(Offer::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(Offer::getId, Comparator.nullsLast(Comparator.reverseOrder()));
  }

  /** Score de conveniencia sin señal de interacción (inquiryCount = 0) — ver overload de 3 parámetros. */
  public double score(Offer offer, OffsetDateTime now) {
    return score(offer, now, 0);
  }

  /** Score de conveniencia de una oferta puntual — ver javadoc de clase para el detalle de cada señal. */
  public double score(Offer offer, OffsetDateTime now, int inquiryCount) {
    return urgencyScore(offer, now)
        + discountScore(offer)
        + qualityScore(offer)
        + originScore(offer)
        + freshnessScore(offer, now)
        + interactionScore(offer, inquiryCount);
  }

  private double urgencyScore(Offer offer, OffsetDateTime now) {
    OffsetDateTime validUntil = offer.getValidUntil();
    if (validUntil == null || now == null) {
      return 0;
    }
    Duration untilExpiry = Duration.between(now, validUntil);
    if (untilExpiry.isNegative()) {
      return 0; // ya vencida — no aporta urgencia, aunque no debería llegar así desde listPublic.
    }
    if (untilExpiry.compareTo(Duration.ofHours(48)) <= 0) {
      return URGENCY_WITHIN_48H;
    }
    if (untilExpiry.compareTo(Duration.ofDays(7)) <= 0) {
      return URGENCY_WITHIN_7D;
    }
    return 0;
  }

  private double discountScore(Offer offer) {
    String text = offer.getDiscountText();
    if (text == null || text.isBlank()) {
      return 0;
    }
    double score = 0;
    Matcher percentMatcher = PERCENT_PATTERN.matcher(text);
    if (percentMatcher.find()) {
      double percent = Double.parseDouble(percentMatcher.group(1));
      score += Math.min(percent, DISCOUNT_PERCENT_CAP) * DISCOUNT_PERCENT_WEIGHT;
    }
    if (TWO_FOR_ONE_PATTERN.matcher(text).find()) {
      score += DISCOUNT_2X1;
    }
    if (FREE_PATTERN.matcher(text).find()) {
      score += DISCOUNT_FREE;
    }
    return score;
  }

  private double qualityScore(Offer offer) {
    double score = 0;
    if (offer.getPhotoUrl() != null && !offer.getPhotoUrl().isBlank()) {
      score += QUALITY_PHOTO;
    }
    String description = offer.getDescription();
    if (description != null && description.length() >= QUALITY_DESCRIPTION_MIN_LENGTH) {
      score += QUALITY_DESCRIPTION;
    }
    return score;
  }

  private double originScore(Offer offer) {
    String origin = offer.getOrigin();
    if (Offer.ORIGIN_MANUAL.equals(origin) || Offer.ORIGIN_WHATSAPP_FORWARD.equals(origin)) {
      return ORIGIN_LOCAL;
    }
    return 0; // ORIGIN_SCRAPED_SOURCE u origen desconocido/null.
  }

  private double freshnessScore(Offer offer, OffsetDateTime now) {
    OffsetDateTime createdAt = offer.getCreatedAt();
    if (createdAt == null || now == null) {
      return 0;
    }
    Duration age = Duration.between(createdAt, now);
    if (age.isNegative()) {
      return 0; // createdAt en el futuro respecto a "now" — no debería pasar, defensivo.
    }
    return age.compareTo(Duration.ofHours(FRESHNESS_WINDOW_HOURS)) < 0 ? FRESHNESS_BONUS : 0;
  }

  /**
   * Interacción real del barrio (fase 3) — ver javadoc de clase para el
   * porqué del tope. {@code viewCount} y {@code likeCount} son señales
   * débiles (un click no cuesta nada) por eso pesan poco (0.2 y 2.0);
   * {@code inquiryCount} es la señal fuerte (un vecino escribió al comercio)
   * por eso pesa más (5.0) — igual las tres quedan acotadas por el mismo
   * tope de +25.
   */
  private double interactionScore(Offer offer, int inquiryCount) {
    double raw = offer.getViewCount() * INTERACTION_VIEW_WEIGHT
        + inquiryCount * INTERACTION_INQUIRY_WEIGHT
        + offer.getLikeCount() * INTERACTION_LIKE_WEIGHT;
    return Math.min(INTERACTION_CAP, raw);
  }
}
