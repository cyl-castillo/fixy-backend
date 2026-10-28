package com.fixy.backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Veredicto DETERMINISTA sobre la opinión de la IA (AVISA_IA_F1_CONTRATO.md). El LLM ignora
 * instrucciones críticas, así que él solo opina (booleanos + confianza) y la decisión es esta
 * función con umbrales. Fase 1: APROBAR es solo una sugerencia para el moderador.
 */
public final class BarrioAiVerdict {

  public static final String APROBAR = "APROBAR";
  public static final String DUDOSO = "DUDOSO";
  public static final String ERROR = "ERROR";
  public static final String SIN_IA = "SIN_IA";

  public static final double MIN_CONFIDENCE = 0.80;

  static final Map<String, String> REASON_LABELS = Map.of(
      "no_parece_problema_publico", "no parece un problema en la vía pública",
      "no_coincide_categoria", "no coincide con la categoría",
      "personas", "se ven caras",
      "matriculas", "se ven matrículas",
      "inapropiado", "contenido inapropiado",
      "foto_mala", "foto de mala calidad",
      "baja_confianza", "baja confianza");

  private BarrioAiVerdict() {}

  /** Lo que la IA devolvió (ya validado: tipos completos y confianza en [0,1]). */
  public record Opinion(
      boolean showsPublicIssue,
      boolean matchesCategory,
      String suggestedCategory,
      boolean hasPeopleFaces,
      boolean hasLicensePlates,
      boolean offensiveOrInappropriate,
      boolean imageQualityOk,
      double confidence,
      String reason) {}

  public record Result(String verdict, List<String> reasons) {}

  public static Result evaluate(Opinion o) {
    List<String> reasons = new ArrayList<>();
    if (!o.showsPublicIssue()) reasons.add("no_parece_problema_publico");
    if (!o.matchesCategory()) reasons.add("no_coincide_categoria");
    if (o.hasPeopleFaces()) reasons.add("personas");
    if (o.hasLicensePlates()) reasons.add("matriculas");
    if (o.offensiveOrInappropriate()) reasons.add("inapropiado");
    if (!o.imageQualityOk()) reasons.add("foto_mala");
    if (!(o.confidence() >= MIN_CONFIDENCE)) reasons.add("baja_confianza");
    return new Result(reasons.isEmpty() ? APROBAR : DUDOSO, List.copyOf(reasons));
  }

  /** Para Telegram / UI: "no coincide con la categoría, se ven caras". */
  public static String humanReasons(List<String> codes) {
    return codes.stream().map(c -> REASON_LABELS.getOrDefault(c, c)).collect(java.util.stream.Collectors.joining(", "));
  }
}
