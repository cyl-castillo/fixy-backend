package com.fixy.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.ServiceCategory;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 2: los enums {@link CoverageZone} y {@link ServiceCategory} son fachada del catálogo.
 * Cada constante existe en el YAML y cada entrada del YAML tiene su constante, y todo lo que el
 * enum devuelve coincide con lo que devolvía antes (snapshot del código viejo) y con el catálogo.
 */
class EnumFacadeParityTest {

  private final DomainCatalog catalog = DomainCatalog.get();
  private final JsonNode snap = LegacySnapshot.load();

  @Test
  void cadaConstanteDeZonaExisteEnElYamlYViceversa() {
    List<String> constants = Arrays.stream(CoverageZone.values()).map(Enum::name).toList();
    List<String> yaml = catalog.zones().stream().map(ZoneDef::id).toList();
    assertThat(constants).as("mismas zonas y mismo orden").isEqualTo(yaml);
    for (CoverageZone zone : CoverageZone.values()) {
      ZoneDef def = catalog.zoneById(zone.name()).orElseThrow();
      assertThat(zone.label()).isEqualTo(def.label());
      assertThat(zone.parent().map(Enum::name)).isEqualTo(def.parent());
    }
  }

  @Test
  void cadaConstanteDeCategoriaExisteEnElYamlYViceversa() {
    List<String> constants = Arrays.stream(ServiceCategory.values()).map(Enum::name).toList();
    List<String> yaml = catalog.categories().stream().map(CategoryDef::enumName).toList();
    assertThat(constants).as("mismas categorías y mismo orden").isEqualTo(yaml);
    for (ServiceCategory category : ServiceCategory.values()) {
      CategoryDef def = catalog.categoryByEnumName(category.name()).orElseThrow();
      assertThat(category.id()).isEqualTo(def.id());
      assertThat(category.label()).isEqualTo(def.label());
      assertThat(category.isMvp()).isEqualTo(def.mvp());
      assertThat(category.keywords()).isEqualTo(def.keywords());
      assertThat(category.hasPriceRange()).isEqualTo(def.hasPriceRange());
      assertThat(category.priceRangeLabel()).isEqualTo(def.priceRangeLabel());
      assertThat(ServiceCategory.intakeHintForId(category.id())).isEqualTo(def.intakeHint());
    }
  }

  @Test
  void losDatosDelEnumCoincidenConElSnapshotDelCodigoViejo() {
    JsonNode zones = snap.get("zones");
    CoverageZone[] values = CoverageZone.values();
    assertThat(values).hasSize(zones.size());
    for (int i = 0; i < values.length; i++) {
      assertThat(values[i].name()).isEqualTo(zones.get(i).get("id").asText());
      assertThat(values[i].label()).isEqualTo(zones.get(i).get("label").asText());
      assertThat(values[i].parent().map(Enum::name).orElse(null))
          .isEqualTo(LegacySnapshot.textOrNull(zones.get(i).get("parent")));
    }
    assertThat(CoverageZone.LABELS).isEqualTo(LegacySnapshot.strings(snap.get("LABELS")));
    assertThat((Set<String>) new TreeSet<>(CoverageZone.NORMALIZED_TOKENS))
        .isEqualTo(new TreeSet<>(LegacySnapshot.strings(snap.get("NORMALIZED_TOKENS"))));

    JsonNode categories = snap.get("categories");
    ServiceCategory[] cats = ServiceCategory.values();
    assertThat(cats).hasSize(categories.size());
    for (int i = 0; i < cats.length; i++) {
      JsonNode expected = categories.get(i);
      assertThat(cats[i].name()).isEqualTo(expected.get("enumName").asText());
      assertThat(cats[i].id()).isEqualTo(expected.get("id").asText());
      assertThat(cats[i].label()).isEqualTo(expected.get("label").asText());
      assertThat(cats[i].isMvp()).isEqualTo(expected.get("mvp").asBoolean());
      assertThat(cats[i].keywords()).isEqualTo(LegacySnapshot.strings(expected.get("keywords")));
      assertThat(cats[i].priceRangeLabel()).isEqualTo(LegacySnapshot.textOrNull(expected.get("priceRangeLabel")));
      assertThat(ServiceCategory.intakeHintForId(cats[i].id()))
          .isEqualTo(LegacySnapshot.textOrNull(expected.get("intakeHint")));
    }
    assertThat(ServiceCategory.MVP_IDS).isEqualTo(LegacySnapshot.strings(snap.get("MVP_IDS")));
    assertThat(ServiceCategory.MVP_LABELS).isEqualTo(LegacySnapshot.strings(snap.get("MVP_LABELS")));
    assertThat(ServiceCategory.ALL_IDS_INCLUDING_OTRO)
        .isEqualTo(LegacySnapshot.strings(snap.get("ALL_IDS_INCLUDING_OTRO")));
  }

  @Test
  void elComportamientoDelEnumCoincideConElCodigoViejo() {
    JsonNode zoneBehavior = snap.get("zoneBehavior");
    for (JsonNode row : zoneBehavior.get("fromLabel")) {
      String in = LegacySnapshot.textOrNull(row.get("in"));
      assertThat(CoverageZone.fromLabel(in).map(Enum::name).orElse(null))
          .as("fromLabel(%s)", in).isEqualTo(LegacySnapshot.textOrNull(row.get("out")));
      assertThat(CoverageZone.isCovered(in)).as("isCovered(%s)", in).isEqualTo(row.get("covered").asBoolean());
      assertThat(CoverageZone.normalize(in)).as("normalize(%s)", in).isEqualTo(row.get("normalized").asText());
    }
    for (JsonNode row : zoneBehavior.get("covers")) {
      String[] parts = row.asText().split("\t", -1);
      String p = LegacySnapshot.nullable(parts[0]);
      String l = LegacySnapshot.nullable(parts[1]);
      assertThat(CoverageZone.covers(p, l)).as("covers(%s, %s)", p, l).isEqualTo(Boolean.parseBoolean(parts[2]));
    }
    for (JsonNode row : zoneBehavior.get("unrecognized")) {
      String a = LegacySnapshot.textOrNull(row.get("a"));
      String b = LegacySnapshot.textOrNull(row.get("b"));
      assertThat(CoverageZone.unrecognized(a, b)).isEqualTo(LegacySnapshot.strings(row.get("out")));
    }
    for (JsonNode row : snap.get("detectFromText")) {
      String in = row.get("in").asText();
      assertThat(ServiceCategory.detectFromText(in).map(ServiceCategory::id).orElse(null))
          .as("detectFromText(\"%s\")", in).isEqualTo(LegacySnapshot.textOrNull(row.get("out")));
    }
    for (JsonNode row : snap.get("refineCategoryId")) {
      String[] parts = row.asText().split("\t", -1);
      assertThat(ServiceCategory.refineCategoryId(LegacySnapshot.nullable(parts[0]), LegacySnapshot.nullable(parts[1])))
          .isEqualTo(LegacySnapshot.nullable(parts[2]));
    }
    for (JsonNode row : snap.get("humanLabel")) {
      String id = LegacySnapshot.textOrNull(row.get("id"));
      assertThat(ServiceCategory.humanLabel(id)).isEqualTo(row.get("label").asText());
      assertThat(ServiceCategory.priceRangeLabelForId(id)).isEqualTo(LegacySnapshot.textOrNull(row.get("range")));
      assertThat(ServiceCategory.fromId(id).map(Enum::name).orElse(null))
          .isEqualTo(LegacySnapshot.textOrNull(row.get("fromId")));
    }
  }
}
