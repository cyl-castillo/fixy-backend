package com.fixy.backend.model;

import java.util.Locale;

/**
 * Marca de tráfico sintético (pruebas): la fuente única que consultan TODOS
 * los guards anti-ruido (schedulers, Telegram, pushes, bandeja del proveedor,
 * cobranzas). Canónico: "[smoke]" en cualquier parte del texto. Convención de
 * Carlos (2026-07-30): también vale la palabra "smoke" al INICIO del mensaje
 * ("smoke necesito un plomero..."). Solo al inicio a propósito: la palabra
 * suelta en medio de un texto real no debe marcar un pedido como prueba.
 */
public final class SmokeTraffic {

  private SmokeTraffic() {
  }

  public static boolean marks(String text) {
    if (text == null) {
      return false;
    }
    String normalized = text.trim().toLowerCase(Locale.ROOT);
    return normalized.contains("[smoke]")
        || normalized.equals("smoke")
        || normalized.startsWith("smoke ")
        || normalized.startsWith("smoke:")
        || normalized.startsWith("smoke,");
  }
}
