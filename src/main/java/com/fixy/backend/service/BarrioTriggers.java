package com.fixy.backend.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parser determinista (sin LLM) de los disparadores del circuito Barrio por WhatsApp.
 * "pozo negro", barométrica y cámara séptica NUNCA disparan: son pedidos de servicio de Fixy,
 * no reclamos de la calle.
 *
 * <p>Se distinguen términos FUERTES (inequívocos de la vía pública: bache, luminaria…) de
 * AMBIGUOS (poda, tala, ramas, troncos: también son un pedido de jardinería). Los ambiguos solo
 * deben disparar cuando el vecino no tiene una conversación de Fixy activa; esa decisión la toma
 * {@link BarrioWhatsAppService}, acá solo se clasifica.
 */
public final class BarrioTriggers {

  public static final String BACHE = "bache";
  public static final String PODA = "poda";
  public static final String LUMINARIA = "luminaria";

  /** Resultado del parseo: categoría y si el disparador es ambiguo. */
  public record Match(String category, boolean ambiguous) {}

  private static final Pattern VETO = Pattern.compile(
      "\\bpozo\\s+negro\\b|\\bbarometric\\w*|\\bcamara\\s+septic\\w*|\\bdesagote\\b");

  private static final Pattern BACHE_RX = Pattern.compile(
      "\\bbaches?\\b|\\bpozos?\\s+en\\s+(?:la\\s+)?(?:calle|calzada)\\b|\\bcalle\\s+rota\\b|\\bcalles\\s+rotas\\b");
  private static final Pattern LUMINARIA_RX = Pattern.compile(
      "\\bluminarias?\\b|\\balumbrado\\b|\\bfoco\\s+de\\s+la\\s+calle\\b|\\bpostes?\\s+de\\s+luz\\b");
  private static final Pattern PODA_RX = Pattern.compile(
      "\\brestos\\s+de\\s+poda\\b|\\bpodas?\\b|\\btalas?\\b|\\bramas\\b|\\btroncos?\\b");

  private BarrioTriggers() {
  }

  public static Optional<Match> parse(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String norm = normalize(text);
    if (VETO.matcher(norm).find()) {
      return Optional.empty();
    }
    if (BACHE_RX.matcher(norm).find()) {
      return Optional.of(new Match(BACHE, false));
    }
    if (LUMINARIA_RX.matcher(norm).find()) {
      return Optional.of(new Match(LUMINARIA, false));
    }
    if (PODA_RX.matcher(norm).find()) {
      return Optional.of(new Match(PODA, true));
    }
    return Optional.empty();
  }

  /** Minúsculas y sin tildes ("Barométrica" → "barometrica"). */
  static String normalize(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
  }
}
