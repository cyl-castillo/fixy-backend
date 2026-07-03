package com.fixy.backend.service;

/**
 * Normalización simple de números de teléfono uruguayos para poder agrupar
 * leads por "mismo cliente". No existe ninguna utilidad de este tipo en el
 * repo (Lead.phone es un String libre cargado por el cliente/ops sin
 * validación previa), así que esto es intencionalmente simple:
 *
 * 1. Quita todo lo que no sea dígito (espacios, guiones, paréntesis, "+").
 * 2. Si el número resultante arranca con el prefijo país "598" y tiene más
 *    de 8 dígitos después de sacarlo, lo saca (ej. "59899123456" -> "99123456").
 * 3. Si no tenía prefijo país, y arranca con "0" (formato nacional con
 *    cero de larga distancia, ej. "099123456"), saca ese "0" líder para que
 *    quede en el mismo formato que el caso anterior ("099123456" -> "99123456").
 *
 * Resultado: "099123456", "59899123456" y "+598 99 123 456" normalizan todos
 * a "99123456". No intenta validar longitud, formato de celular vs. fijo, ni
 * detectar números claramente inválidos: es una agrupación best-effort para
 * métricas, no una validación de datos de negocio.
 */
final class PhoneNumberNormalizer {

  private PhoneNumberNormalizer() {
  }

  static String normalize(String rawPhone) {
    if (rawPhone == null) {
      return "";
    }

    String digitsOnly = rawPhone.replaceAll("[^0-9]", "");

    if (digitsOnly.startsWith("598") && digitsOnly.length() > 8) {
      digitsOnly = digitsOnly.substring(3);
    } else if (digitsOnly.startsWith("0") && digitsOnly.length() > 8) {
      digitsOnly = digitsOnly.substring(1);
    }

    return digitsOnly;
  }
}
