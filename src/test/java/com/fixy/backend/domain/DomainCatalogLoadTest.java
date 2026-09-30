package com.fixy.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Core Fase 2: carga del catálogo — override externo válido, fallback al classpath (nunca romper
 * prod por un archivo de override) y el objetivo del cambio: una zona/categoría agregada SOLO en
 * el YAML existe para cobertura, detección y listas aunque no tenga constante en el código.
 */
class DomainCatalogLoadTest {

  @TempDir Path tmp;

  private String builtinYaml() throws IOException {
    try (var in = getClass().getResourceAsStream("/domain/home-services.yml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private Path write(String name, String content) throws IOException {
    Path file = tmp.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private static final String COSTA_DE_ORO_ZONE = """
        - id: COSTA_DE_ORO
          label: "Costa de Oro"
          parent: null
          aliases: ["Costa de oro"]
          detect: {tokens: ["costa de oro"]}
      """;

  private static final String LIMPIEZA_CATEGORY = """
        - id: limpieza
          enumName: LIMPIEZA
          label: "limpieza"
          mvp: true
          keywords: ["limpieza", "limpiar"]
          priceMin: 500
          priceMax: 900
          intakeHint: "qué hay que limpiar"
          classifierDescription: "limpieza de casas."
          menuDescription: "Limpieza de hogar"
      """;

  /** El YAML del jar con una zona y una categoría más (las agrega antes de "otro"). */
  private String withExtras() throws IOException {
    String yaml = builtinYaml();
    yaml = yaml.replace("\ncategories:\n", "\ncategories:\n" + LIMPIEZA_CATEGORY);
    // La zona nueva va al final del bloque zones (justo antes del comentario de categorías).
    int categoriesDoc = yaml.indexOf("\n# Categorías. Campos:");
    return yaml.substring(0, categoriesDoc) + "\n" + COSTA_DE_ORO_ZONE.stripTrailing() + "\n"
        + yaml.substring(categoriesDoc);
  }

  @Test
  void sinOverrideUsaElClasspath() {
    DomainCatalog catalog = DomainCatalog.load(null);
    assertThat(catalog.source()).isEqualTo("classpath:domain/home-services.yml");
    assertThat(catalog.zones()).hasSize(12);
    assertThat(catalog.categories()).hasSize(11);
  }

  @Test
  void unOverrideValidoReemplazaAlClasspath() throws IOException {
    Path file = write("domain.yml", withExtras());
    DomainCatalog catalog = DomainCatalog.load(file.toString());
    assertThat(catalog.source()).isEqualTo(file.toString());
    assertThat(catalog.zones()).hasSize(13);
  }

  @Test
  void unaZonaAgregadaSoloEnElYamlExisteParaCoberturaYDeteccion() throws IOException {
    DomainCatalog catalog = DomainCatalog.load(write("domain.yml", withExtras()).toString());

    assertThat(catalog.isCovered("Costa de Oro")).isTrue();
    assertThat(catalog.isCovered("costa de oro")).isTrue();
    assertThat(catalog.covers("Costa de Oro", "costa de oro")).isTrue();
    assertThat(catalog.zoneByLabel("COSTA DE ORO")).map(ZoneDef::label).contains("Costa de Oro");
    assertThat(catalog.detectZone("vivo en la Costa de Oro")).map(ZoneDef::id).contains("COSTA_DE_ORO");
    assertThat(catalog.zoneLabels()).endsWith("Costa de Oro");
    // Es un paraguas más: se lista al final, después de las específicas.
    assertThat(catalog.promptZones().stream().map(ZoneDef::label).toList())
        .endsWith("Ciudad de la Costa", "Costa de Oro");
    // El catálogo del jar no la conoce: cada catálogo es independiente.
    assertThat(DomainCatalog.load(null).isCovered("Costa de Oro")).isFalse();
    // Las zonas de siempre siguen igual.
    assertThat(catalog.covers("Ciudad de la Costa", "Lagomar")).isTrue();
    assertThat(catalog.covers("Costa de Oro", "Lagomar")).isFalse();
  }

  @Test
  void unaCategoriaAgregadaSoloEnElYamlSeDetectaYAparece() throws IOException {
    DomainCatalog catalog = DomainCatalog.load(write("domain.yml", withExtras()).toString());

    assertThat(catalog.categoryById("limpieza")).isPresent();
    assertThat(catalog.detectCategory("necesito limpiar la casa")).map(CategoryDef::id).contains("limpieza");
    assertThat(catalog.mvpIds()).contains("limpieza");
    assertThat(catalog.allCategoryIds()).contains("limpieza").endsWith("otro");
    assertThat(catalog.humanLabel("limpieza")).isEqualTo("limpieza");
    assertThat(catalog.priceRangeLabel("limpieza")).isEqualTo("$500–900");
    assertThat(catalog.intakeHintForId("limpieza")).isEqualTo("qué hay que limpiar");
    // Lo que ya existía se detecta igual (la categoría nueva va primero: keywords propias, sin choque).
    assertThat(catalog.detectCategory("se me rompió la canilla")).map(CategoryDef::id).contains("plomeria");
  }

  @Test
  void unOverrideInexistenteCaeAlClasspath() {
    DomainCatalog catalog = DomainCatalog.load(tmp.resolve("no-existe.yml").toString());
    assertThat(catalog.source()).isEqualTo("classpath:domain/home-services.yml");
  }

  @Test
  void unOverrideRotoCaeAlClasspath() throws IOException {
    DomainCatalog catalog = DomainCatalog.load(write("roto.yml", "zones: [\n  - esto no cierra").toString());
    assertThat(catalog.source()).isEqualTo("classpath:domain/home-services.yml");

    DomainCatalog notAMap = DomainCatalog.load(write("lista.yml", "- a\n- b\n").toString());
    assertThat(notAMap.source()).isEqualTo("classpath:domain/home-services.yml");
  }

  @Test
  void unOverrideQueLeFaltaUnIdDelCatalogoBaseCaeAlClasspath() throws IOException {
    // Sin la categoría "mandados": la fachada ServiceCategory.MANDADOS quedaría sin datos.
    String yaml = builtinYaml();
    int start = yaml.indexOf("  - id: mandados");
    int end = yaml.indexOf("  - id: otro");
    Path file = write("sin-mandados.yml", yaml.substring(0, start) + yaml.substring(end));

    DomainCatalog catalog = DomainCatalog.load(file.toString());
    assertThat(catalog.source()).isEqualTo("classpath:domain/home-services.yml");
    assertThat(catalog.categoryById("mandados")).isPresent();
  }

  @Test
  void unOverrideConCamposInvalidosCaeAlClasspath() throws IOException {
    String yaml = builtinYaml().replace("    label: \"Solymar\"\n", "");
    DomainCatalog catalog = DomainCatalog.load(write("sin-label.yml", yaml).toString());
    assertThat(catalog.source()).isEqualTo("classpath:domain/home-services.yml");
  }

  @Test
  void parseFallaConMensajeClaroSiElCatalogoEsInvalido() throws IOException {
    String parentInexistente = builtinYaml().replaceFirst("parent: CIUDAD_DE_LA_COSTA", "parent: NO_EXISTE");
    assertThatThrownBy(() -> DomainCatalog.parse(
        new ByteArrayInputStream(parentInexistente.getBytes(StandardCharsets.UTF_8)), "test.yml"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("parent inexistente NO_EXISTE");

    String duplicada = builtinYaml().replaceFirst("- id: LAGOMAR", "- id: SOLYMAR");
    assertThatThrownBy(() -> DomainCatalog.parse(
        new ByteArrayInputStream(duplicada.getBytes(StandardCharsets.UTF_8)), "test.yml"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("zona duplicada SOLYMAR");
  }
}
