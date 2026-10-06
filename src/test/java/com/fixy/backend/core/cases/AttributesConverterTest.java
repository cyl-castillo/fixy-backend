package com.fixy.backend.core.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Core Fase 3 (CORE_FASE3_CONTRATO.md, pieza 2): Lead.attributes como texto JSON. */
class AttributesConverterTest {

  private final AttributesConverter converter = new AttributesConverter();

  @Test
  void nullYVacioSonLoMismoEnLasDosDirecciones() {
    assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("{}");
    assertThat(converter.convertToDatabaseColumn(Map.of())).isEqualTo("{}");
    assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    assertThat(converter.convertToEntityAttribute("")).isEmpty();
    assertThat(converter.convertToEntityAttribute("{}")).isEmpty();
    assertThat(converter.convertToEntityAttribute("null")).isEmpty();
  }

  @Test
  void ida_y_vueltaConservaTiposYAnidados() {
    Map<String, Object> original = new LinkedHashMap<>();
    original.put("texto", "hola");
    original.put("numero", 42);
    original.put("booleano", true);
    original.put("anidado", Map.of("lista", List.of(1, 2, 3)));

    String json = converter.convertToDatabaseColumn(original);
    assertThat(converter.convertToEntityAttribute(json)).isEqualTo(original);
  }

  @Test
  void elResultadoEsUnMapaMutable() {
    Map<String, Object> loaded = converter.convertToEntityAttribute("{}");
    loaded.put("k", "v");
    assertThat(loaded).containsEntry("k", "v");
  }

  @Test
  void jsonInvalidoNoSePierdeNiLanza() {
    for (String roto : new String[] {"{no es json", "[1,2,3]", "\"suelto\"", "42"}) {
      Map<String, Object> loaded = converter.convertToEntityAttribute(roto);
      assertThat(loaded).containsOnlyKeys(AttributesConverter.INVALID_KEY);
      assertThat(loaded.get(AttributesConverter.INVALID_KEY)).isEqualTo(roto);
      // Y al volver a guardar el texto original sobrevive dentro del JSON.
      assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(loaded))).isEqualTo(loaded);
    }
  }

  @Test
  void muchosInvalidosDistintosNoRompenElDedupeAcotado() {
    for (int i = 0; i < 300; i++) {
      assertThat(converter.convertToEntityAttribute("roto-" + i))
          .containsEntry(AttributesConverter.INVALID_KEY, "roto-" + i);
    }
  }
}
