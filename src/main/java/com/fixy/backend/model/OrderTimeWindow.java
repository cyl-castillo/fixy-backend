package com.fixy.backend.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Ventana horaria del pedido estructurado (Refundación de Fixy, fase 1,
 * contrato REFUNDACION_FASE1_CONTRATO.md §3). Fuente única de verdad para:
 * qué valores acepta {@code POST /api/public/orders}, la etiqueta legible
 * que ve el vecino/proveedor, y la urgencia derivada — mismo criterio
 * "alta|media|baja" que ya usa {@code Lead.urgency} en todo el resto del
 * sistema (ver {@link ServiceCategory} y {@link CoverageZone}, agregados por
 * la misma razón: evitar que esto se desincronice entre el request DTO, el
 * mapeo de urgencia y el copy que arma el mensaje al cliente/proveedor).
 */
public enum OrderTimeWindow {
  HOY("hoy", "hoy", "alta"),
  MANANA_AM("manana_am", "mañana por la mañana", "media"),
  MANANA_PM("manana_pm", "mañana por la tarde", "media"),
  ESTA_SEMANA("esta_semana", "esta semana", "baja"),
  COORDINAR("coordinar", "a coordinar", "baja");

  private final String id;
  private final String label;
  private final String urgency;

  OrderTimeWindow(String id, String label, String urgency) {
    this.id = id;
    this.label = label;
    this.urgency = urgency;
  }

  /** Valor persistido en {@code Lead.timeWindow} / el request del pedido. */
  public String id() {
    return id;
  }

  /** Texto legible en español para el cliente y para el aviso al proveedor. */
  public String label() {
    return label;
  }

  /** Urgencia derivada ("alta"|"media"|"baja"), mismo dominio que Lead.urgency. */
  public String urgency() {
    return urgency;
  }

  public static final List<String> IDS = Arrays.stream(values()).map(OrderTimeWindow::id).toList();

  public static Optional<OrderTimeWindow> fromId(String rawId) {
    if (rawId == null || rawId.isBlank()) {
      return Optional.empty();
    }
    String normalized = rawId.toLowerCase(Locale.ROOT).trim();
    return Arrays.stream(values()).filter(w -> w.id.equals(normalized)).findFirst();
  }

  /** Etiqueta legible para el id dado, o el id crudo si no se reconoce. */
  public static String labelForId(String rawId) {
    return fromId(rawId).map(OrderTimeWindow::label).orElse(rawId == null ? "" : rawId);
  }

  /** Urgencia derivada para el id dado, "media" si no se reconoce (mismo
   * default que el resto del sistema usa cuando no hay urgencia declarada). */
  public static String urgencyForId(String rawId) {
    return fromId(rawId).map(OrderTimeWindow::urgency).orElse("media");
  }
}
