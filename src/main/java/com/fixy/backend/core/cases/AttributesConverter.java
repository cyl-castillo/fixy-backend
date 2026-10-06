package com.fixy.backend.core.cases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core Fase 3 (ver CORE_FASE3_CONTRATO.md): {@code Lead.attributes} como texto JSON. Se guarda
 * como {@code text}/{@code varchar}, no como {@code jsonb}: un binding JSON de Hibernate contra
 * Postgres no se prueba en la suite (H2) y, si falla, rompe el INSERT de todos los leads.
 *
 * <p>{@code null} y {@code {}} son lo mismo en las dos direcciones (el mapa vacío). Un texto de
 * la base que no es un objeto JSON NO se pierde: {@code Lead} no tiene {@code @DynamicUpdate}, así
 * que el primer {@code save} pisaría la columna; se devuelve {@code {"_invalid": "<texto>"}} y el
 * texto original sobrevive dentro del JSON que se guarda de vuelta. Hibernate convierte en cada
 * carga y en cada dirty-check (y hay 15 schedulers), así que el WARN se deduplica por hash del texto
 * en un conjunto acotado.
 */
@Converter
public class AttributesConverter implements AttributeConverter<Map<String, Object>, String> {

  /** Clave bajo la que se conserva un texto de la base que no era JSON de objeto. */
  public static final String INVALID_KEY = "_invalid";

  private static final Logger log = LoggerFactory.getLogger(AttributesConverter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final String EMPTY = "{}";
  private static final int MAX_WARNED = 100;

  /** Hashes de los textos inválidos ya avisados; acotado a {@link #MAX_WARNED} (descarta el más viejo). */
  private static final Set<Integer> WARNED = Collections.synchronizedSet(new LinkedHashSet<>());

  @Override
  public String convertToDatabaseColumn(Map<String, Object> attribute) {
    if (attribute == null || attribute.isEmpty()) {
      return EMPTY;
    }
    try {
      return MAPPER.writeValueAsString(attribute);
    } catch (JsonProcessingException ex) {
      // Un valor no serializable es un bug del que llama: mejor que falle el save a perder el dato.
      throw new IllegalArgumentException("Lead.attributes no es serializable a JSON: " + ex.getOriginalMessage(), ex);
    }
  }

  @Override
  public Map<String, Object> convertToEntityAttribute(String dbData) {
    if (dbData == null || dbData.isBlank()) {
      return new LinkedHashMap<>();
    }
    try {
      LinkedHashMap<String, Object> parsed = MAPPER.readValue(dbData, MAP_TYPE);
      // El literal JSON "null" parsea a null: equivale al mapa vacío.
      return parsed == null ? new LinkedHashMap<>() : parsed;
    } catch (JsonProcessingException | RuntimeException ex) {
      warnOnce(dbData);
      Map<String, Object> invalid = new LinkedHashMap<>();
      invalid.put(INVALID_KEY, dbData);
      return invalid;
    }
  }

  private static void warnOnce(String text) {
    int hash = text.hashCode();
    synchronized (WARNED) {
      if (!WARNED.add(hash)) {
        return;
      }
      if (WARNED.size() > MAX_WARNED) {
        WARNED.remove(WARNED.iterator().next());
      }
    }
    String head = text.length() > 80 ? text.substring(0, 80) + "..." : text;
    log.warn("Lead.attributes con JSON inválido, se conserva bajo '{}': {}", INVALID_KEY, head.replace('\n', ' '));
  }
}
