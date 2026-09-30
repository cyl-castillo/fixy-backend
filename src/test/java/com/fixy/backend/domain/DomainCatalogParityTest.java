package com.fixy.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 2, paridad de la pieza 1: el catálogo cargado de {@code domain/home-services.yml}
 * reproduce EXACTAMENTE lo que producían los enums antes de moverse (snapshot generado corriendo
 * el código viejo). Cubre datos (labels, alias, keywords en orden, precios, hints) y comportamiento
 * (fromLabel/isCovered/covers/unrecognized/detectCategory/refineCategoryId/humanLabel).
 */
class DomainCatalogParityTest {

  private final DomainCatalog catalog = DomainCatalog.get();
  private final JsonNode snap = LegacySnapshot.load();

  @Test
  void zonasCoincidenConElEnumViejo() {
    JsonNode zones = snap.get("zones");
    assertThat(catalog.zones()).hasSize(zones.size());
    for (int i = 0; i < zones.size(); i++) {
      JsonNode expected = zones.get(i);
      ZoneDef actual = catalog.zones().get(i);
      assertThat(actual.id()).as("orden de zonas, posición %d", i).isEqualTo(expected.get("id").asText());
      assertThat(actual.label()).isEqualTo(expected.get("label").asText());
      assertThat(actual.parentId()).isEqualTo(LegacySnapshot.textOrNull(expected.get("parent")));
      assertThat(actual.aliases()).isEqualTo(LegacySnapshot.strings(expected.get("aliases")));
    }
    assertThat(catalog.zoneLabels()).isEqualTo(LegacySnapshot.strings(snap.get("LABELS")));
    assertThat(new TreeSet<>(catalog.normalizedTokens()))
        .isEqualTo(new TreeSet<>(LegacySnapshot.strings(snap.get("NORMALIZED_TOKENS"))));
  }

  @Test
  void categoriasCoincidenConElEnumViejo() {
    JsonNode categories = snap.get("categories");
    assertThat(catalog.categories()).hasSize(categories.size());
    for (int i = 0; i < categories.size(); i++) {
      JsonNode expected = categories.get(i);
      CategoryDef actual = catalog.categories().get(i);
      assertThat(actual.enumName()).as("orden de categorías, posición %d", i).isEqualTo(expected.get("enumName").asText());
      assertThat(actual.id()).isEqualTo(expected.get("id").asText());
      assertThat(actual.label()).isEqualTo(expected.get("label").asText());
      assertThat(actual.mvp()).isEqualTo(expected.get("mvp").asBoolean());
      assertThat(actual.keywords()).as("keywords de %s (el orden es comportamiento)", actual.id())
          .isEqualTo(LegacySnapshot.strings(expected.get("keywords")));
      assertThat(actual.priceMin()).isEqualTo(LegacySnapshot.intOrNull(expected.get("priceMin")));
      assertThat(actual.priceMax()).isEqualTo(LegacySnapshot.intOrNull(expected.get("priceMax")));
      assertThat(actual.priceRangeLabel()).isEqualTo(LegacySnapshot.textOrNull(expected.get("priceRangeLabel")));
      assertThat(actual.intakeHint()).isEqualTo(LegacySnapshot.textOrNull(expected.get("intakeHint")));
    }
    assertThat(catalog.mvpIds()).isEqualTo(LegacySnapshot.strings(snap.get("MVP_IDS")));
    assertThat(catalog.mvpLabels()).isEqualTo(LegacySnapshot.strings(snap.get("MVP_LABELS")));
    assertThat(catalog.allCategoryIds()).isEqualTo(LegacySnapshot.strings(snap.get("ALL_IDS_INCLUDING_OTRO")));
  }

  @Test
  void resolucionDeZonasYCoberturaCoincidenConElCodigoViejo() {
    JsonNode zoneBehavior = snap.get("zoneBehavior");
    for (JsonNode row : zoneBehavior.get("fromLabel")) {
      String in = LegacySnapshot.textOrNull(row.get("in"));
      assertThat(catalog.zoneByLabel(in).map(ZoneDef::id).orElse(null))
          .as("zoneByLabel(%s)", in).isEqualTo(LegacySnapshot.textOrNull(row.get("out")));
      assertThat(catalog.isCovered(in)).as("isCovered(%s)", in).isEqualTo(row.get("covered").asBoolean());
      assertThat(catalog.normalizeZone(in)).as("normalizeZone(%s)", in).isEqualTo(row.get("normalized").asText());
    }
    int checked = 0;
    for (JsonNode row : zoneBehavior.get("covers")) {
      String[] parts = row.asText().split("\t", -1);
      String p = LegacySnapshot.nullable(parts[0]);
      String l = LegacySnapshot.nullable(parts[1]);
      assertThat(catalog.covers(p, l)).as("covers(%s, %s)", p, l).isEqualTo(Boolean.parseBoolean(parts[2]));
      checked++;
    }
    assertThat(checked).isGreaterThan(400);
    for (JsonNode row : zoneBehavior.get("unrecognized")) {
      String a = LegacySnapshot.textOrNull(row.get("a"));
      String b = LegacySnapshot.textOrNull(row.get("b"));
      assertThat(catalog.unrecognized(a, b)).as("unrecognized(%s, %s)", a, b)
          .isEqualTo(LegacySnapshot.strings(row.get("out")));
    }
  }

  @Test
  void deteccionYDesempateDeCategoriaCoincidenConElCodigoViejo() {
    for (JsonNode row : snap.get("detectFromText")) {
      String in = row.get("in").asText();
      assertThat(catalog.detectCategory(in).map(CategoryDef::id).orElse(null))
          .as("detectCategory(\"%s\")", in).isEqualTo(LegacySnapshot.textOrNull(row.get("out")));
    }
    assertThat(catalog.detectCategory(null)).isEmpty();
    int checked = 0;
    for (JsonNode row : snap.get("refineCategoryId")) {
      String[] parts = row.asText().split("\t", -1);
      String text = LegacySnapshot.nullable(parts[0]);
      String id = LegacySnapshot.nullable(parts[1]);
      assertThat(catalog.refineCategoryId(text, id)).as("refineCategoryId(%s, %s)", text, id)
          .isEqualTo(LegacySnapshot.nullable(parts[2]));
      checked++;
    }
    assertThat(checked).isGreaterThan(500);
    for (JsonNode row : snap.get("humanLabel")) {
      String id = LegacySnapshot.textOrNull(row.get("id"));
      assertThat(catalog.humanLabel(id)).as("humanLabel(%s)", id).isEqualTo(row.get("label").asText());
      assertThat(catalog.priceRangeLabel(id)).as("priceRangeLabel(%s)", id)
          .isEqualTo(LegacySnapshot.textOrNull(row.get("range")));
      assertThat(catalog.intakeHintForId(id)).as("intakeHintForId(%s)", id)
          .isEqualTo(LegacySnapshot.textOrNull(row.get("hint")));
      assertThat(catalog.categoryById(id).map(CategoryDef::enumName).orElse(null))
          .as("categoryById(%s)", id).isEqualTo(LegacySnapshot.textOrNull(row.get("fromId")));
    }
  }

  @Test
  void elOrdenDePromptEsEspecificasElParaguasDespues() {
    List<String> labels = catalog.promptZones().stream().map(ZoneDef::label).toList();
    assertThat(labels).containsExactly(
        "Solymar", "Lagomar", "El Pinar", "Shangrilá", "Barra de Carrasco", "Parque Miramar",
        "San José de Carrasco", "Lomas de Solymar", "Colinas de Solymar", "Montes de Solymar",
        "Aeroparque", "Ciudad de la Costa");
    assertThat(catalog.areaUnknown()).isEqualTo("sin definir");
  }
}
