package com.fixy.backend.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Lectura del snapshot {@code domain/legacy-snapshot.json}: lo que producían los enums
 * {@code CoverageZone}/{@code ServiceCategory} ANTES de Core Fase 2 (generado corriendo el
 * código viejo, no transcripto a mano). Es el ancla de paridad: si el YAML o el catálogo
 * cambian un valor o un orden observable, los tests que lo usan fallan.
 */
final class LegacySnapshot {

  private LegacySnapshot() {}

  static JsonNode load() {
    return read("/domain/legacy-snapshot.json");
  }

  static JsonNode loadAreaDetection() {
    return read("/domain/legacy-area-detection.json");
  }

  private static JsonNode read(String resource) {
    try (InputStream in = LegacySnapshot.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("falta el recurso de test " + resource);
      }
      return new ObjectMapper().readTree(in);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.isNull() ? null : n.asText()));
    return out;
  }

  /** El snapshot serializa null como el texto "null" en las matrices de una sola línea. */
  static String nullable(String raw) {
    return "null".equals(raw) ? null : raw;
  }

  static Integer intOrNull(JsonNode n) {
    return n == null || n.isNull() ? null : n.asInt();
  }

  static String textOrNull(JsonNode n) {
    return n == null || n.isNull() ? null : n.asText();
  }
}
