package com.fixy.backend.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 3 (CORE_FASE3_CONTRATO.md, pieza 2): el playbook tiene un {@code id} (el caseType de
 * sus casos) y una sección opcional {@code attributes[]} que valida los atributos libres del caso.
 * El de Fixy no declara ninguno; los tests de validación usan una variante de prueba con tres.
 */
class PlaybookAttributesTest {

  private static String realYaml() throws IOException {
    try (var in = PlaybookAttributesTest.class.getResourceAsStream("/domain/home-services-playbook.yml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Playbook parse(String yaml) {
    return Playbook.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)),
        "test-playbook.yml", DomainCatalog.get());
  }

  /** El playbook real con tres atributos declarados (number, boolean y string con enum). */
  private static Playbook conTresAtributos() throws IOException {
    return parse(realYaml().replace("attributes: []", """
        attributes:
          - name: rooms
            type: number
          - name: hasPets
            type: boolean
          - name: access
            type: string
            enumSource: [portero, llave, abierto]
        """));
  }

  @Test
  void elPlaybookDeFixyTieneIdYNoDeclaraAtributos() {
    Playbook real = Playbook.get();
    assertThat(real.id()).isEqualTo("home-services");
    assertThat(real.attributes()).isEmpty();
    assertThatThrownBy(() -> real.validateAttribute("rooms", 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no declarado")
        .hasMessageContaining("rooms");
  }

  @Test
  void unPlaybookSinIdNoArranca() throws IOException {
    assertThatThrownBy(() -> parse(realYaml().replace("id: home-services\n", "")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("id");
  }

  @Test
  void aceptaLosAtributosDeclaradosConSuTipo() throws IOException {
    Playbook playbook = conTresAtributos();
    assertThat(playbook.attributes()).extracting(Playbook.Attribute::name)
        .containsExactly("rooms", "hasPets", "access");
    assertThatCode(() -> {
      playbook.validateAttribute("rooms", 3);
      playbook.validateAttribute("rooms", 2.5);
      playbook.validateAttribute("hasPets", true);
      playbook.validateAttribute("access", "llave");
    }).doesNotThrowAnyException();
  }

  @Test
  void unaClaveNoDeclaradaSeRechazaConMensajeClaro() throws IOException {
    assertThatThrownBy(() -> conTresAtributos().validateAttribute("floor", 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("floor")
        .hasMessageContaining("rooms, hasPets, access");
  }

  @Test
  void unTipoDistintoSeRechaza() throws IOException {
    Playbook playbook = conTresAtributos();
    assertThatThrownBy(() -> playbook.validateAttribute("rooms", "tres"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rooms").hasMessageContaining("number").hasMessageContaining("String");
    assertThatThrownBy(() -> playbook.validateAttribute("hasPets", "si"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("boolean");
    assertThatThrownBy(() -> playbook.validateAttribute("access", 7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("string");
    assertThatThrownBy(() -> playbook.validateAttribute("rooms", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("null");
  }

  @Test
  void unValorFueraDelEnumDeclaradoSeRechaza() throws IOException {
    assertThatThrownBy(() -> conTresAtributos().validateAttribute("access", "ventana"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("access").hasMessageContaining("portero");
  }

  @Test
  void unTipoDeAtributoDesconocidoHaceFallarElArranque() throws IOException {
    assertThatThrownBy(() -> parse(realYaml().replace("attributes: []", """
        attributes:
          - name: rooms
            type: decimal
        """)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rooms").hasMessageContaining("decimal");
  }

  @Test
  void laSeccionAttributesEsOpcional() throws IOException {
    Playbook sinSeccion = parse(realYaml().replace("attributes: []\n", ""));
    assertThat(sinSeccion.attributes()).isEmpty();
  }
}
