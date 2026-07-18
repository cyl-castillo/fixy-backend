package com.fixy.backend.dto;

import java.util.List;

/**
 * "Mis números" (self-service, Ola 2): estadísticas derivadas de datos que
 * ya existen (leads asignados + contadores del provider), sin tabla nueva.
 * Honestidad: sin datos de aceptación todavía, {@code acceptanceRate} es
 * null (el front debe mostrar "todavía no hay números", nunca 0% dramático
 * para un proveedor nuevo — mismo criterio que ratingAverage null con
 * ratingCount 0 en {@link ProviderSelfResponse}).
 */
public record ProviderStatsResponse(
    Double acceptanceRate,
    Integer acceptedCount,
    Integer rejectedCount,
    Double ratingAverage,
    Integer ratingCount,
    List<WeeklyCompleted> completedByWeek
) {
  /**
   * Un balde semanal para las mini-barras del panel. {@code weekStart} es
   * el lunes de esa semana (ISO), para que el front las ordene y etiquete
   * sin recalcular fechas.
   */
  public record WeeklyCompleted(String weekStart, int count) {
  }
}
