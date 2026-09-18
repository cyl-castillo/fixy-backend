package com.fixy.backend.model;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ventanas de disponibilidad del proveedor (Tier 2, contrato §A.1). Formato
 * plano, sin JSON (H2 en dev/test, columna varchar en prod):
 *
 * <pre>lun=08-20;mar=08-20;mie=08-20;jue=08-20;vie=08-20;sab=09-13;dom=</pre>
 *
 * Un día sin rango (vacío después del {@code =}, o el día ausente de la
 * cadena) significa "no disponible ese día". Un solo rango {@code HH-HH}
 * por día, en hora local {@code America/Montevideo}. {@code null} o cadena
 * en blanco significa "siempre disponible" — el default histórico de los
 * proveedores que ya existían antes de esta feature.
 */
public final class AvailabilityWindows {

  private static final AvailabilityWindows ALWAYS_OPEN = new AvailabilityWindows(new EnumMap<>(DayOfWeek.class));

  /** Orden fijo lun→dom para {@link #format()} — determinístico sin importar
   * el orden en que se insertaron las entradas del mapa. */
  private static final Map<DayOfWeek, String> DAY_CODES = new LinkedHashMap<>();
  static {
    DAY_CODES.put(DayOfWeek.MONDAY, "lun");
    DAY_CODES.put(DayOfWeek.TUESDAY, "mar");
    DAY_CODES.put(DayOfWeek.WEDNESDAY, "mie");
    DAY_CODES.put(DayOfWeek.THURSDAY, "jue");
    DAY_CODES.put(DayOfWeek.FRIDAY, "vie");
    DAY_CODES.put(DayOfWeek.SATURDAY, "sab");
    DAY_CODES.put(DayOfWeek.SUNDAY, "dom");
  }

  /** Rango [open, close) en hora local, por día. Un día ausente = cerrado ese día. */
  private final Map<DayOfWeek, int[]> openRangesByDay;

  private AvailabilityWindows(Map<DayOfWeek, int[]> openRangesByDay) {
    this.openRangesByDay = openRangesByDay;
  }

  /** {@code null}/vacío = siempre disponible (compatibilidad con proveedores existentes). */
  public static AvailabilityWindows parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return ALWAYS_OPEN;
    }
    Map<DayOfWeek, int[]> ranges = new EnumMap<>(DayOfWeek.class);
    String[] segments = raw.split(";");
    for (String segment : segments) {
      String trimmed = segment.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        throw invalid(raw, "cada franja debe tener el formato dia=HH-HH, ej. lun=08-20");
      }
      String dayCode = trimmed.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
      String rangePart = trimmed.substring(eq + 1).trim();
      DayOfWeek day = dayFor(dayCode, raw);
      if (rangePart.isEmpty()) {
        // Día explícitamente sin rango: no disponible ese día. No se agrega
        // al mapa (ausente = cerrado), pero es una entrada válida.
        continue;
      }
      int dash = rangePart.indexOf('-');
      if (dash <= 0 || dash == rangePart.length() - 1) {
        throw invalid(raw, "la franja de " + dayCode + " debe ser HH-HH, ej. 08-20");
      }
      int open = parseHour(rangePart.substring(0, dash), raw);
      int close = parseHour(rangePart.substring(dash + 1), raw);
      if (open >= close) {
        throw invalid(raw, "la hora de inicio debe ser menor a la de cierre en " + dayCode);
      }
      ranges.put(day, new int[] {open, close});
    }
    return new AvailabilityWindows(ranges);
  }

  private static int parseHour(String raw, String original) {
    try {
      int hour = Integer.parseInt(raw.trim());
      if (hour < 0 || hour > 24) {
        throw invalid(original, "las horas deben estar entre 0 y 24");
      }
      return hour;
    } catch (NumberFormatException ex) {
      throw invalid(original, "hora inválida: " + raw);
    }
  }

  private static DayOfWeek dayFor(String code, String original) {
    return DAY_CODES.entrySet().stream()
        .filter(e -> e.getValue().equals(code))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow(() -> invalid(original,
            "día inválido: " + code + " (usar lun, mar, mie, jue, vie, sab, dom)"));
  }

  private static ResponseStatusException invalid(String raw, String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "availabilityWindows inválido (\"" + raw + "\"): " + reason);
  }

  /** true si el proveedor no cargó ninguna ventana (siempre disponible). */
  public boolean isAlwaysOpen() {
    return openRangesByDay.isEmpty();
  }

  public boolean isOpenAt(ZonedDateTime when) {
    if (isAlwaysOpen()) {
      return true;
    }
    Objects.requireNonNull(when, "when");
    int[] range = openRangesByDay.get(when.getDayOfWeek());
    if (range == null) {
      return false;
    }
    int minuteOfDay = when.toLocalTime().toSecondOfDay() / 60;
    return minuteOfDay >= range[0] * 60 && minuteOfDay < range[1] * 60;
  }

  /**
   * Próxima apertura desde {@code from} (inclusive si ya está abierto).
   * Siempre disponible → devuelve {@code from} tal cual. Recorre hasta 8
   * días (la semana completa + el mismo día de vuelta) buscando el primer
   * horario de apertura.
   */
  public ZonedDateTime nextOpening(ZonedDateTime from) {
    Objects.requireNonNull(from, "from");
    if (isAlwaysOpen()) {
      return from;
    }
    if (isOpenAt(from)) {
      return from;
    }
    for (int i = 0; i <= 7; i++) {
      ZonedDateTime candidateDay = from.plusDays(i);
      int[] range = openRangesByDay.get(candidateDay.getDayOfWeek());
      if (range == null) {
        continue;
      }
      ZonedDateTime opening = candidateDay.toLocalDate().atStartOfDay(candidateDay.getZone())
          .plusHours(range[0]);
      if (!opening.isBefore(from)) {
        return opening;
      }
    }
    // No debería pasar (7 días alcanzan siempre que haya al menos un día
    // abierto) — defensivo: si no hay NINGÚN día abierto, no hay próxima
    // apertura real; devolvemos from + 7 días para no romper el caller.
    return from.plusDays(7);
  }

  /** Serialización de vuelta al formato de {@link #parse(String)}. Vacía
   * (todos los días implícitos) si es siempre disponible. */
  public String format() {
    if (isAlwaysOpen()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<DayOfWeek, String> entry : DAY_CODES.entrySet()) {
      if (sb.length() > 0) {
        sb.append(';');
      }
      sb.append(entry.getValue()).append('=');
      int[] range = openRangesByDay.get(entry.getKey());
      if (range != null) {
        sb.append(String.format("%02d-%02d", range[0], range[1]));
      }
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return "AvailabilityWindows[" + format() + "]";
  }
}
