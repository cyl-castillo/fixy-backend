package com.fixy.backend.service;

import com.fixy.backend.model.BarrioReport;
import com.fixy.backend.model.SmokeTraffic;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Regla determinista de auto-publicación de Avisá (AVISA_IA_F2_CONTRATO.md). La IA solo opina; esto
 * decide. Se publica solo si se cumplen TODAS: interruptor encendido, veredicto APROBAR, confianza
 * >= umbral, sigue PENDING_REVIEW, sin [smoke] y tope diario no alcanzado. La IA nunca rechaza.
 */
public final class BarrioAutoPublishPolicy {

  public static final ZoneId ZONE = ZoneId.of("America/Montevideo");

  public enum Decision { PUBLISH, DAILY_CAP_REACHED, NO }

  private BarrioAutoPublishPolicy() {
  }

  public static Decision decide(
      boolean switchOn, String aiVerdict, Double confidence, double minConfidence,
      String status, String description, long publishedToday, int dailyCap
  ) {
    if (!switchOn) return Decision.NO;
    if (!BarrioAiVerdict.APROBAR.equals(aiVerdict)) return Decision.NO;
    if (confidence == null || confidence.isNaN() || confidence < minConfidence) return Decision.NO;
    if (!BarrioReport.PENDING_REVIEW.equals(status)) return Decision.NO;
    if (SmokeTraffic.marks(description)) return Decision.NO;
    if (publishedToday >= dailyCap) return Decision.DAILY_CAP_REACHED;
    return Decision.PUBLISH;
  }

  /** Inicio (inclusive) del día de Montevideo que contiene {@code now}. */
  public static Instant dayStart(Instant now) {
    return ZonedDateTime.ofInstant(now, ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
  }

  /** Inicio (exclusive como fin) del día siguiente en Montevideo. */
  public static Instant dayEnd(Instant now) {
    return ZonedDateTime.ofInstant(now, ZONE).toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant();
  }
}
