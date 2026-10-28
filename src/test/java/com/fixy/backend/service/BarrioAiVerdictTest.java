package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.service.BarrioAiVerdict.Opinion;
import org.junit.jupiter.api.Test;

class BarrioAiVerdictTest {

  private static Opinion ok() {
    return new Opinion(true, true, "bache", false, false, false, true, 0.92, "pozo en la calzada");
  }

  @Test
  void todoEnOrdenAprueba() {
    var r = BarrioAiVerdict.evaluate(ok());
    assertThat(r.verdict()).isEqualTo("APROBAR");
    assertThat(r.reasons()).isEmpty();
  }

  @Test
  void confianzaExactamenteEnElUmbralAprueba() {
    var o = new Opinion(true, true, "bache", false, false, false, true, 0.80, "x");
    assertThat(BarrioAiVerdict.evaluate(o).verdict()).isEqualTo("APROBAR");
  }

  @Test
  void cadaMotivoPorSeparado() {
    assertMotivo(new Opinion(false, true, "ninguna", false, false, false, true, 0.9, "x"), "no_parece_problema_publico");
    assertMotivo(new Opinion(true, false, "poda", false, false, false, true, 0.9, "x"), "no_coincide_categoria");
    assertMotivo(new Opinion(true, true, "bache", true, false, false, true, 0.9, "x"), "personas");
    assertMotivo(new Opinion(true, true, "bache", false, true, false, true, 0.9, "x"), "matriculas");
    assertMotivo(new Opinion(true, true, "bache", false, false, true, true, 0.9, "x"), "inapropiado");
    assertMotivo(new Opinion(true, true, "bache", false, false, false, false, 0.9, "x"), "foto_mala");
    assertMotivo(new Opinion(true, true, "bache", false, false, false, true, 0.79, "x"), "baja_confianza");
  }

  @Test
  void variosMotivosEnOrdenDelContrato() {
    var o = new Opinion(true, true, "bache", true, true, false, true, 0.5, "x");
    var r = BarrioAiVerdict.evaluate(o);
    assertThat(r.verdict()).isEqualTo("DUDOSO");
    assertThat(r.reasons()).containsExactly("personas", "matriculas", "baja_confianza");
  }

  private static void assertMotivo(Opinion o, String motivo) {
    var r = BarrioAiVerdict.evaluate(o);
    assertThat(r.verdict()).isEqualTo("DUDOSO");
    assertThat(r.reasons()).containsExactly(motivo);
  }
}
