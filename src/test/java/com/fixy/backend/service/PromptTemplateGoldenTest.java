package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixy.backend.domain.DomainCatalog;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 2, pieza 2: los prompts son plantillas rellenadas desde {@link DomainCatalog}.
 * Golden: el prompt renderizado con el YAML actual == el fixture de
 * {@code src/test/resources/prompts/expected/}, que se generó desde el texto ANTERIOR de los
 * prompts. Diferencias a propósito respecto del texto viejo (todas de formato o de una
 * desincronización que la plantilla cierra): las listas que salen del catálogo (servicios,
 * zonas) van en una sola línea en vez de cortadas a mano, y "Montes de Solymar" —zona
 * cubierta, presente en el schema y en isCovered pero ausente de las listas escritas a mano
 * en los prompts— ahora aparece.
 */
class PromptTemplateGoldenTest {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

  private static String resource(String path) {
    try (InputStream in = PromptTemplateGoldenTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("falta " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static Set<String> placeholdersOf(String template) {
    Set<String> found = new TreeSet<>();
    Matcher m = PLACEHOLDER.matcher(template);
    while (m.find()) {
      found.add(m.group(1));
    }
    return found;
  }

  @Test
  void elPromptDelTurnoRenderizadoCoincideConElGolden() {
    assertThat(PromptLoader.load("prompts/lead-agent-system.md"))
        .isEqualTo(resource("/prompts/expected/lead-agent-system.md"));
  }

  @Test
  void elPromptDelClasificadorRenderizadoCoincideConElGolden() {
    assertThat(PromptLoader.load("prompts/intake-classifier.md"))
        .isEqualTo(resource("/prompts/expected/intake-classifier.md"));
    // Y la variante para formatted(...) es idéntica (el catálogo actual no tiene '%').
    assertThat(PromptLoader.loadFormatTemplate("prompts/intake-classifier.md"))
        .isEqualTo(resource("/prompts/expected/intake-classifier.md"));
  }

  @Test
  void losPlaceholdersDeCadaPromptSonExactamenteLosEsperados() {
    assertThat(placeholdersOf(PromptLoader.loadRaw("prompts/lead-agent-system.md")))
        .containsExactlyInAnyOrder("business.intro", "services.covered", "zones.covered", "categories.notes");
    assertThat(placeholdersOf(PromptLoader.loadRaw("prompts/intake-classifier.md")))
        .containsExactlyInAnyOrder("business.name", "business.region", "categories.count",
            "categories.classifier_lines", "zones.area_values");
  }

  @Test
  void elPromptRenderizadoNoDejaPlaceholdersSinRellenar() {
    assertThat(PromptLoader.load("prompts/lead-agent-system.md")).doesNotContain("{{");
    assertThat(PromptLoader.load("prompts/intake-classifier.md")).doesNotContain("{{");
  }

  @Test
  void elClasificadorConservaSusPlaceholdersDeFormatted() {
    String rendered = PromptLoader.loadFormatTemplate("prompts/intake-classifier.md");
    assertThat(rendered).contains("Nombre: %s").contains("Mensaje: %s");
    assertThat(rendered.formatted("a", "b", "c", "d", "e", "f", "g", "h", "i")).contains("Mensaje: i");
  }

  @Test
  void unPlaceholderDesconocidoFallaConMensajeClaro() {
    assertThatThrownBy(() -> PromptRenderer.render("hola {{no.existe}}", DomainCatalog.get()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("{{no.existe}}");
  }

  @Test
  void elRenderEscapaElPorcentajeDeLosDatosSoloParaFormatted() throws IOException {
    String yaml = resource("/domain/home-services.yml")
        .replace("classifierDescription: \"cualquier pedido que no encaje claramente en las anteriores, o pedidos vagos.\"",
            "classifierDescription: \"descuento del 10% en todo.\"");
    DomainCatalog catalog = DomainCatalog.parse(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");

    String template = "{{categories.classifier_lines}}\nMensaje: %s";
    assertThat(PromptRenderer.render(template, catalog)).contains("10% en todo");
    assertThat(PromptRenderer.renderForFormat(template, catalog).formatted("hola"))
        .contains("10% en todo").endsWith("Mensaje: hola");
  }

  @Test
  void unaZonaYUnaCategoriaAgregadasAlYamlLleganAlPrompt() throws IOException {
    String yaml = resource("/domain/home-services.yml");
    yaml = yaml.replace("\ncategories:\n", """

        categories:
          - id: limpieza
            enumName: LIMPIEZA
            label: "limpieza"
            mvp: true
            keywords: ["limpieza"]
            classifierDescription: "limpieza de casas."
            coverageNote: "limpieza profunda"
            promptNotes: |-
              Si el pedido es de limpieza: preguntá los metros cuadrados.
        """);
    int categoriesDoc = yaml.indexOf("\n# Categorías. Campos:");
    yaml = yaml.substring(0, categoriesDoc) + """

          - id: COSTA_DE_ORO
            label: "Costa de Oro"
            parent: null
            aliases: []
        """ + yaml.substring(categoriesDoc);
    DomainCatalog catalog = DomainCatalog.parse(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");

    String lead = PromptRenderer.render(PromptLoader.loadRaw("prompts/lead-agent-system.md"), catalog);
    assertThat(lead)
        .contains("limpieza (limpieza profunda), plomería")
        .contains("Ciudad de la Costa, Costa de Oro.")
        .contains("Si el pedido es de limpieza: preguntá los metros cuadrados.")
        .contains("Si el pedido es de pastelería");

    String intake = PromptRenderer.render(PromptLoader.loadRaw("prompts/intake-classifier.md"), catalog);
    assertThat(intake)
        .contains("estas 11 categorias exactas")
        .contains("- limpieza: limpieza de casas.")
        .contains("Ciudad de la Costa, Costa de Oro, sin definir.");
  }
}
