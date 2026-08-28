package com.fixy.backend.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parsing determinista de {@code Offer.discountText} (texto libre: "20%
 * off", "hasta 30%", "2x1", "3x2", "gratis", "$500") — pieza única y
 * reutilizable, sin heurística de lenguaje natural (regla de oro del
 * proyecto: el LLM de prod ignora instrucciones, todo lo crítico va en
 * código). Antes de esta clase, {@code OfferRankingService} tenía sus
 * propios regex privados; ahora ambos ({@code OfferRankingService} para el
 * score, {@code OfferAnalysisService} para el análisis "¿conviene?" de fase
 * 3) usan las mismas piezas de acá — un solo lugar donde "20%" significa 20.
 *
 * <p>Dos superficies distintas, a propósito:
 * <ul>
 *   <li>{@link #extractPercent}, {@link #hasTwoForOne}, {@link #hasFree}:
 *   detecciones atómicas, cada una independiente — {@code
 *   OfferRankingService} las usa por separado porque sus señales de score
 *   son acumulables (un texto "20% off + 2x1 + envío gratis" suma las tres a
 *   la vez). No tocar esta forma sin revisar {@code OfferRankingServiceTest}.</li>
 *   <li>{@link #discountPercent}: un ÚNICO porcentaje consolidado y
 *   comparable entre ofertas, para el análisis de fase 3 (no tiene sentido
 *   ahí sumar señales — "¿cuál es EL descuento de esta oferta?" es una
 *   pregunta con una sola respuesta o ninguna).</li>
 * </ul>
 */
@Component
public class DiscountParser {

  /** "2x1" equivale a pagar la mitad — ver {@link #discountPercent}. */
  static final int TWO_FOR_ONE_PERCENT = 50;

  /** "3x2" equivale a pagar 2 de cada 3 — 1/3 ≈ 33% (redondeo a la baja, entero). */
  static final int THREE_FOR_TWO_PERCENT = 33;

  private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3})\\s*%");
  private static final Pattern TWO_FOR_ONE_PATTERN = Pattern.compile("2\\s*x\\s*1", Pattern.CASE_INSENSITIVE);
  private static final Pattern THREE_FOR_TWO_PATTERN = Pattern.compile("3\\s*x\\s*2", Pattern.CASE_INSENSITIVE);
  private static final Pattern FREE_PATTERN = Pattern.compile("gratis|free", Pattern.CASE_INSENSITIVE);

  /**
   * Primer número seguido de "%" en el texto ("20%", "hasta 30% de
   * descuento" → 30), sin tope (el tope anti-outlier es decisión del caller,
   * ver {@code OfferRankingService.DISCOUNT_PERCENT_CAP}). Null si no hay
   * ningún patrón "NN%".
   */
  public Integer extractPercent(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = PERCENT_PATTERN.matcher(text);
    if (matcher.find()) {
      return Integer.parseInt(matcher.group(1));
    }
    return null;
  }

  /** "2x1", "2 X 1" (case-insensitive, tolera espacios) — NO matchea "3x2" ni ningún otro NxM. */
  public boolean hasTwoForOne(String text) {
    return text != null && TWO_FOR_ONE_PATTERN.matcher(text).find();
  }

  /** "3x2", "3 X 2" (case-insensitive, tolera espacios). */
  public boolean hasThreeForTwo(String text) {
    return text != null && THREE_FOR_TWO_PATTERN.matcher(text).find();
  }

  /** "gratis" o "free", case-insensitive. */
  public boolean hasFree(String text) {
    return text != null && FREE_PATTERN.matcher(text).find();
  }

  /**
   * Porcentaje de descuento ÚNICO y comparable entre ofertas (fase 3,
   * {@code OfferAnalysisService}) — null cuando el texto no es comparable:
   * vacío, monto fijo ("$500"), o "gratis" sin precio base de referencia.
   *
   * <p>Prioridad cuando el texto combina señales (ej. "2x1 + 10% en la
   * segunda unidad"): porcentaje explícito primero — es la señal más
   * literal, el comercio lo tipeó así — después "2x1" (50%), después "3x2"
   * (33%). "gratis"/monto fijo nunca ganan: no hay con qué comparar.
   */
  public Integer discountPercent(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    Integer percent = extractPercent(text);
    if (percent != null) {
      return percent;
    }
    if (hasTwoForOne(text)) {
      return TWO_FOR_ONE_PERCENT;
    }
    if (hasThreeForTwo(text)) {
      return THREE_FOR_TWO_PERCENT;
    }
    return null;
  }
}
