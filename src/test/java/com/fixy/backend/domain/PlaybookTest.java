package com.fixy.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 2, pieza 3: el playbook declara el contrato del turno (campos, acciones, requisitos de
 * matching, teléfono de contacto, tools) y de él salen el schema y el bloque de formato. La
 * paridad exacta con el schema/texto de antes está en LeadAgentTurnContractTest.
 */
class PlaybookTest {

  private final Playbook playbook = Playbook.get();

  private static String resource(String path) throws IOException {
    try (var in = PlaybookTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Playbook parse(String yaml, DomainCatalog catalog) {
    return Playbook.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test-playbook.yml", catalog);
  }

  @Test
  void declaraLosCamposDelTurnoEnSuOrden() {
    assertThat(playbook.fields()).extracting(Playbook.Field::name)
        .containsExactly("category", "zone", "urgency", "phone", "name", "address", "details");
    assertThat(playbook.actionTypes()).containsExactly("none", "escalate");
    assertThat(playbook.requiredForMatching()).containsExactly("category", "zone");
    assertThat(playbook.contactAskField()).isEqualTo("phone");
    assertThat(playbook.tools()).containsExactly("fixy_list_services", "fixy_create_order");
  }

  @Test
  void losEnumsDeCategoriaYZonaDerivanDelCatalogo() {
    DomainCatalog catalog = DomainCatalog.get();
    List<String> categoryEnum = playbook.enumValues(playbook.field("category").orElseThrow());
    assertThat(categoryEnum).isEqualTo(java.util.stream.Stream.concat(
        catalog.mvpIds().stream(), java.util.stream.Stream.of("otro")).toList());

    List<String> zoneEnum = playbook.enumValues(playbook.field("zone").orElseThrow());
    // Todas las zonas del catálogo (con las etiquetas canónicas) + "otro", sin repetidos.
    assertThat(zoneEnum).containsAll(catalog.zoneLabels()).endsWith("Ciudad de la Costa", "otro");
    assertThat(zoneEnum).doesNotHaveDuplicates().hasSize(catalog.zones().size() + 1);

    assertThat(playbook.enumValues(playbook.field("urgency").orElseThrow())).containsExactly("alta", "media", "baja");
    assertThat(playbook.enumValues(playbook.field("phone").orElseThrow())).isNull();
  }

  @Test
  void unaZonaYUnaCategoriaNuevasDelCatalogoLleganAlSchemaYAlBloqueDeFormato() throws IOException {
    String yaml = resource("/domain/home-services.yml");
    yaml = yaml.replace("\ncategories:\n", """

        categories:
          - id: limpieza
            enumName: LIMPIEZA
            label: "limpieza"
            mvp: true
            keywords: ["limpieza"]
        """);
    int categoriesDoc = yaml.indexOf("\n# Categorías. Campos:");
    yaml = yaml.substring(0, categoriesDoc) + """

          - id: COSTA_DE_ORO
            label: "Costa de Oro"
            parent: null
            aliases: []
        """ + yaml.substring(categoriesDoc);
    DomainCatalog catalog = DomainCatalog.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");
    Playbook extended = parse(resource("/domain/home-services-playbook.yml"), catalog);

    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>)
        ((Map<String, Object>) extended.turnSchema().get("properties")).get("extracted")).get("properties");
    @SuppressWarnings("unchecked")
    List<String> categories = (List<String>) ((Map<String, Object>) props.get("category")).get("enum");
    @SuppressWarnings("unchecked")
    List<String> zones = (List<String>) ((Map<String, Object>) props.get("zone")).get("enum");

    assertThat(categories).startsWith("limpieza", "plomeria").endsWith("mandados", "otro");
    // Las zonas de enumOrder primero; los paraguas (el de siempre y el nuevo, en orden del catálogo)
    // al final, y después "otro".
    assertThat(zones).startsWith("Solymar", "Lagomar").endsWith("Ciudad de la Costa", "Costa de Oro", "otro");
    assertThat(extended.outputFormatBlock())
        .contains("\"category\": \"limpieza|plomeria|")
        .contains("|Ciudad de la Costa|Costa de Oro|otro|null\"");
  }

  @Test
  void unPlaybookInvalidoFallaConMensajeClaro() throws IOException {
    DomainCatalog catalog = DomainCatalog.get();
    String base = resource("/domain/home-services-playbook.yml");

    assertThatThrownBy(() -> parse(base.replace("requiredForMatching: [category, zone]",
        "requiredForMatching: [category, ciudad]"), catalog))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requiredForMatching menciona un campo inexistente: ciudad");

    assertThatThrownBy(() -> parse(base.replace("enumSource: categories", "enumSource: barrios"), catalog))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("enumSource desconocido: barrios");

    assertThatThrownBy(() -> parse(base.replace("SOLYMAR, LAGOMAR", "SOLYMAR, NO_EXISTE"), catalog))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("zona inexistente: NO_EXISTE");

    assertThatThrownBy(() -> parse(base.replace("tools: [fixy_list_services, fixy_create_order]", ""), catalog))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tools");
  }
}
