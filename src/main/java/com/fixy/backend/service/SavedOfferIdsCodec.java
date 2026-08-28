package com.fixy.backend.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * CSV de ids de {@code Offer} guardados por un suscriptor push
 * ({@code PushSubscription.savedOfferIds}, V22, Fase Push-2) — mismo patrón
 * liviano que {@code Provider.categories}: texto CSV en vez de tabla aparte,
 * el volumen no lo justifica. Única pieza que sabe leer/escribir ese CSV: la
 * usan tanto el alta pública ({@code PushNotificationService.upsertPublicSubscription},
 * que la escribe) como el recordatorio de vencimiento
 * ({@code SavedOfferReminderScheduler}, que la lee y la limpia) — reusarla
 * evita que las dos partes se desincronicen en el separador o en cómo tratan
 * duplicados/ids corruptos.
 */
public final class SavedOfferIdsCodec {

  private SavedOfferIdsCodec() {
  }

  /** Ids únicos, en el orden de aparición; entradas vacías o no numéricas se ignoran (fila corrupta, no rompe el resto). */
  public static List<Long> parse(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    for (String piece : csv.split(",")) {
      String trimmed = piece.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        ids.add(Long.valueOf(trimmed));
      } catch (NumberFormatException ignored) {
        // fila corrupta: se ignora, no rompe el resto del CSV.
      }
    }
    return List.copyOf(ids);
  }

  /** null si la lista queda vacía (o es null) — mismo criterio "vacío = sin guardar" que el resto del modelo. */
  public static String format(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return null;
    }
    LinkedHashSet<Long> unique = new LinkedHashSet<>();
    for (Long id : ids) {
      if (id != null) {
        unique.add(id);
      }
    }
    if (unique.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (Long id : unique) {
      parts.add(String.valueOf(id));
    }
    return String.join(",", parts);
  }
}
