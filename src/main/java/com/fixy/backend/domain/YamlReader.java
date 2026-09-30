package com.fixy.backend.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lectura tipada de un YAML ya parseado a Map/List, con mensajes de error que dicen
 * QUÉ campo de QUÉ archivo está mal (un override roto tiene que poder diagnosticarse
 * desde el WARN del log). Compartido por {@link DomainCatalog} y {@link Playbook}.
 */
final class YamlReader {

  private final String source;

  YamlReader(String source) {
    this.source = source;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object value, String where) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalStateException(where + ": se esperaba un mapa YAML");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : map.entrySet()) {
      result.put(String.valueOf(e.getKey()), e.getValue());
    }
    return result;
  }

  Map<String, Object> map(Map<String, Object> parent, String key, String where) {
    Object value = parent.get(key);
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalStateException(source + ": falta el mapa '" + where + "'");
    }
    return asMap(value, source + ": " + where);
  }

  List<Map<String, Object>> mapList(Map<String, Object> parent, String key, String where) {
    Object value = parent.get(key);
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalStateException(source + ": falta la lista '" + where + "'");
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : list) {
      result.add(asMap(item, source + ": " + where));
    }
    return result;
  }

  String string(Map<String, Object> parent, String key, String where) {
    Object value = parent.get(key);
    if (value == null || String.valueOf(value).isBlank()) {
      throw new IllegalStateException(source + ": falta el campo '" + where + "'");
    }
    return String.valueOf(value);
  }

  String stringOrNull(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    return value == null ? null : String.valueOf(value);
  }

  List<String> stringList(Map<String, Object> parent, String key, String where) {
    Object value = parent.get(key);
    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException(source + ": falta la lista '" + where + "'");
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      result.add(String.valueOf(item));
    }
    return result;
  }

  List<String> stringListOrEmpty(Map<String, Object> parent, String key, String where) {
    return parent.get(key) == null ? List.of() : stringList(parent, key, where);
  }

  boolean bool(Map<String, Object> parent, String key, String where, boolean defaultValue) {
    Object value = parent.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean b)) {
      throw new IllegalStateException(source + ": '" + where + "' tiene que ser true/false");
    }
    return b;
  }

  int integer(Map<String, Object> parent, String key, String where) {
    Object value = parent.get(key);
    if (!(value instanceof Number n)) {
      throw new IllegalStateException(source + ": '" + where + "' tiene que ser un número");
    }
    return n.intValue();
  }

  Integer integerOrNull(Map<String, Object> parent, String key, String where) {
    return parent.get(key) == null ? null : integer(parent, key, where);
  }
}
