package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BarrioTriggersTest {

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
      "hay un bache enorme en mi calle|bache",
      "Hay BACHES en Av. Giannattasio|bache",
      "un pozo en la calle frente a casa|bache",
      "pozo en la calzada de Solymar|bache",
      "la calle rota desde hace meses|bache",
      "hay una luminaria apagada|luminaria",
      "no anda el alumbrado de la cuadra|luminaria",
      "el foco de la calle está quemado|luminaria",
      "se cayó un poste de luz|luminaria",
      "ramas en la vereda|poda",
      "quedaron restos de poda en la esquina|poda",
      "tiraron troncos en la calle|poda",
      "hicieron una tala y dejaron todo|poda",
  })
  void dispara(String texto, String categoria) {
    assertThat(BarrioTriggers.parse(texto)).hasValueSatisfying(m -> assertThat(m.category()).isEqualTo(categoria));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "tengo el pozo negro lleno",
      "necesito la barométrica para el pozo negro",
      "Necesito que venga la Barométrica",
      "se tapó la cámara séptica",
      "tengo un pozo negro y hay un bache en la calle", // el veto gana: es un pedido de servicio
      "se me rompió el aire acondicionado",
      "necesito un plomero urgente",
      "hola",
      "la torta de cumpleaños",
      "",
  })
  void noDispara(String texto) {
    assertThat(BarrioTriggers.parse(texto)).isEmpty();
  }

  @Test
  void pozoNegroSinTildesNiMayusculasTampocoDispara() {
    assertThat(BarrioTriggers.parse("POZO NEGRO")).isEmpty();
    assertThat(BarrioTriggers.parse("Camara Septica llena")).isEmpty();
    assertThat(BarrioTriggers.parse(null)).isEmpty();
  }

  @Test
  void bacheYLuminariaSonFuertesYPodaEsAmbigua() {
    assertThat(BarrioTriggers.parse("bache").orElseThrow().ambiguous()).isFalse();
    assertThat(BarrioTriggers.parse("luminaria").orElseThrow().ambiguous()).isFalse();
    for (String t : List.of("poda", "tala", "ramas", "troncos", "restos de poda")) {
      assertThat(BarrioTriggers.parse(t).orElseThrow().ambiguous()).as(t).isTrue();
    }
  }

  @Test
  void noMatcheaPalabrasQueSoloContienenElDisparador() {
    assertThat(BarrioTriggers.parse("quiero podar el árbol del jardín")).isEmpty();
    assertThat(BarrioTriggers.parse("estaba de talante")).isEmpty();
    assertThat(BarrioTriggers.parse("el bachecito")).isEmpty();
  }
}
