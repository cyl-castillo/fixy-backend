package com.fixy.backend.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Veredicto DETERMINISTA de las pruebas de un cierre (AVISA_CIERRE_C1_CONTRATO.md). La IA solo
 * describe lo que ve; esta función con umbrales decide FUERTE / DEBIL. FUERTE sii no hay ningún
 * motivo descalificante. {@code sin_fecha} se informa pero no descalifica (muchos navegadores borran
 * el EXIF). Mientras la IA no terminó: PENDIENTE. C1 es modo sombra: nada se cierra solo.
 */
public final class BarrioClosureEvidence {

  public static final String FUERTE = "FUERTE";
  public static final String DEBIL = "DEBIL";
  public static final String PENDIENTE = "PENDIENTE";

  public static final double MAX_DISTANCE_M = 50.0;
  public static final double MAX_ACCURACY_M = 100.0;
  public static final int SAME_PHOTO_MAX_HAMMING = 8;
  public static final double MIN_CONFIDENCE = 0.80;

  /** Motivos que se informan pero no descalifican. */
  static final java.util.Set<String> INFORMATIVE = java.util.Set.of("sin_fecha");

  static final Map<String, String> REASON_LABELS = Map.ofEntries(
      Map.entry("sin_ubicacion", "sin ubicación"),
      Map.entry("lejos", "lejos"),
      Map.entry("ubicacion_imprecisa", "ubicación imprecisa"),
      Map.entry("foto_vieja", "foto vieja"),
      Map.entry("misma_foto", "parece la misma foto"),
      Map.entry("ia_otro_lugar", "la IA cree que es otro lugar"),
      Map.entry("ia_sigue_problema", "la IA ve el problema todavía"),
      Map.entry("ia_baja_confianza", "baja confianza de la IA"),
      Map.entry("ia_privacidad", "se ven caras o matrículas"),
      Map.entry("foto_mala", "foto de mala calidad"),
      Map.entry("sin_ia", "sin IA"));

  private BarrioClosureEvidence() {}

  /** Pruebas que no dependen de la IA. {@code distanceM} null = no hubo ubicación. */
  public record Facts(
      Double distanceM, Double accuracyM, OffsetDateTime photoTakenAt, OffsetDateTime reportCreatedAt,
      Integer dhashMinDistance) {}

  /** Lo que la IA devolvió al comparar antes / después (ya validado). */
  public record AiOpinion(
      boolean samePlace, boolean issueGone, boolean reused, boolean hasPeopleFaces,
      boolean hasLicensePlates, boolean imageQualityOk, double confidence, String reason) {}

  public record Result(String verdict, List<String> reasons) {}

  /**
   * @param aiVerdict REVISADO / ERROR / SIN_IA / null (aún sin tocar)
   * @param opinion   solo con REVISADO
   * @param aiAttempts intentos ya hechos (ERROR con menos de {@code maxAttempts} sigue pendiente)
   */
  public static Result evaluate(Facts f, String aiVerdict, AiOpinion opinion, int aiAttempts, int maxAttempts) {
    List<String> reasons = new ArrayList<>();
    if (f.distanceM() == null) {
      reasons.add("sin_ubicacion");
    } else {
      if (f.distanceM() > MAX_DISTANCE_M) reasons.add("lejos");
      if (f.accuracyM() != null && f.accuracyM() > MAX_ACCURACY_M) reasons.add("ubicacion_imprecisa");
    }
    if (f.photoTakenAt() == null) {
      reasons.add("sin_fecha");
    } else if (f.reportCreatedAt() != null && f.photoTakenAt().isBefore(f.reportCreatedAt())) {
      reasons.add("foto_vieja");
    }
    boolean sameByHash = f.dhashMinDistance() != null && f.dhashMinDistance() <= SAME_PHOTO_MAX_HAMMING;
    boolean aiDone = "REVISADO".equals(aiVerdict) && opinion != null;
    boolean aiGaveUp = "SIN_IA".equals(aiVerdict) || ("ERROR".equals(aiVerdict) && aiAttempts >= maxAttempts);
    if (sameByHash || (aiDone && opinion.reused())) reasons.add("misma_foto");
    if (aiDone) {
      if (!opinion.samePlace()) reasons.add("ia_otro_lugar");
      if (!opinion.issueGone()) reasons.add("ia_sigue_problema");
      if (!(opinion.confidence() >= MIN_CONFIDENCE)) reasons.add("ia_baja_confianza");
      if (opinion.hasPeopleFaces() || opinion.hasLicensePlates()) reasons.add("ia_privacidad");
      if (!opinion.imageQualityOk()) reasons.add("foto_mala");
    } else if (aiGaveUp) {
      reasons.add("sin_ia");
    }
    if (!aiDone && !aiGaveUp) {
      return new Result(PENDIENTE, List.copyOf(reasons));
    }
    boolean disqualified = reasons.stream().anyMatch(r -> !INFORMATIVE.contains(r));
    return new Result(disqualified ? DEBIL : FUERTE, List.copyOf(reasons));
  }

  /** Solo los motivos que vienen de la IA (para {@code ai_reasons}). */
  public static List<String> aiReasons(List<String> reasons) {
    return reasons.stream().filter(r -> r.startsWith("ia_") || "foto_mala".equals(r)).toList();
  }

  /** "lejos (320 m), foto vieja": para Telegram; sin los motivos solo informativos. */
  public static String humanReasons(List<String> codes, Double distanceM) {
    return codes.stream()
        .filter(c -> !INFORMATIVE.contains(c))
        .map(c -> "lejos".equals(c) && distanceM != null
            ? "lejos (" + Math.round(distanceM) + " m)" : REASON_LABELS.getOrDefault(c, c))
        .collect(Collectors.joining(", "));
  }

  /** Línea de pruebas para Telegram. */
  public static String telegramLine(
      String verdict, List<String> reasons, Double distanceM, Double aiConfidence,
      Boolean aiSamePlace, Boolean aiIssueGone
  ) {
    if (FUERTE.equals(verdict)) {
      if (aiConfidence == null) return "Pruebas: FUERTES";
      return "Pruebas: FUERTES (IA " + Math.round(aiConfidence * 100) + " %: "
          + (Boolean.TRUE.equals(aiSamePlace) ? "mismo lugar" : "lugar sin confirmar") + ", "
          + (Boolean.TRUE.equals(aiIssueGone) ? "ya no está" : "sigue el problema") + ")";
    }
    if (DEBIL.equals(verdict)) {
      return "Pruebas: débiles — " + humanReasons(reasons, distanceM);
    }
    return "Pruebas: sin evaluar";
  }
}
