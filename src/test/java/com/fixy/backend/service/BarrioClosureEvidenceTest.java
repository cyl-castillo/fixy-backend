package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.service.BarrioClosureEvidence.AiOpinion;
import com.fixy.backend.service.BarrioClosureEvidence.Facts;
import com.fixy.backend.service.BarrioClosureEvidence.Result;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BarrioClosureEvidenceTest {

  private static final OffsetDateTime REPORT = OffsetDateTime.parse("2026-10-01T10:00:00-03:00");
  private static final OffsetDateTime AFTER = OffsetDateTime.parse("2026-10-20T10:00:00-03:00");

  private static Facts good() {
    return new Facts(12.0, 20.0, AFTER, REPORT, 30);
  }

  private static AiOpinion ok() {
    return new AiOpinion(true, true, false, false, false, true, 0.91, "Pozo tapado");
  }

  private static Result eval(Facts f, String aiVerdict, AiOpinion o, int attempts) {
    return BarrioClosureEvidence.evaluate(f, aiVerdict, o, attempts, 3);
  }

  @Test
  void todoBienEsFuerte() {
    Result r = eval(good(), "REVISADO", ok(), 1);
    assertThat(r.verdict()).isEqualTo("FUERTE");
    assertThat(r.reasons()).isEmpty();
  }

  @Test
  void sinFechaSeInformaPeroNoDescalifica() {
    Result r = eval(new Facts(12.0, 20.0, null, REPORT, 30), "REVISADO", ok(), 1);
    assertThat(r.verdict()).isEqualTo("FUERTE");
    assertThat(r.reasons()).containsExactly("sin_fecha");
  }

  @Test
  void sinUbicacion() {
    Result r = eval(new Facts(null, null, AFTER, REPORT, 30), "REVISADO", ok(), 1);
    assertThat(r.verdict()).isEqualTo("DEBIL");
    assertThat(r.reasons()).containsExactly("sin_ubicacion");
  }

  @Test
  void lejosSoloPasadosLos50Metros() {
    assertThat(eval(new Facts(50.0, 10.0, AFTER, REPORT, 30), "REVISADO", ok(), 1).verdict()).isEqualTo("FUERTE");
    Result r = eval(new Facts(50.1, 10.0, AFTER, REPORT, 30), "REVISADO", ok(), 1);
    assertThat(r.verdict()).isEqualTo("DEBIL");
    assertThat(r.reasons()).containsExactly("lejos");
  }

  @Test
  void ubicacionImprecisaPasadosLos100Metros() {
    assertThat(eval(new Facts(5.0, 100.0, AFTER, REPORT, 30), "REVISADO", ok(), 1).verdict()).isEqualTo("FUERTE");
    Result r = eval(new Facts(5.0, 100.5, AFTER, REPORT, 30), "REVISADO", ok(), 1);
    assertThat(r.reasons()).containsExactly("ubicacion_imprecisa");
    assertThat(r.verdict()).isEqualTo("DEBIL");
  }

  @Test
  void fotoVieja() {
    Result r = eval(new Facts(5.0, 10.0, REPORT.minusDays(2), REPORT, 30), "REVISADO", ok(), 1);
    assertThat(r.verdict()).isEqualTo("DEBIL");
    assertThat(r.reasons()).containsExactly("foto_vieja");
  }

  @Test
  void mismaFotoPorHuellaOPorLaIA() {
    assertThat(eval(new Facts(5.0, 10.0, AFTER, REPORT, 8), "REVISADO", ok(), 1).reasons()).containsExactly("misma_foto");
    assertThat(eval(new Facts(5.0, 10.0, AFTER, REPORT, 9), "REVISADO", ok(), 1).verdict()).isEqualTo("FUERTE");
    AiOpinion reused = new AiOpinion(true, true, true, false, false, true, 0.95, "captura");
    Result r = eval(good(), "REVISADO", reused, 1);
    assertThat(r.reasons()).containsExactly("misma_foto");
    assertThat(r.verdict()).isEqualTo("DEBIL");
  }

  @Test
  void motivosDeLaIA() {
    assertThat(eval(good(), "REVISADO", new AiOpinion(false, true, false, false, false, true, 0.9, "x"), 1).reasons())
        .containsExactly("ia_otro_lugar");
    assertThat(eval(good(), "REVISADO", new AiOpinion(true, false, false, false, false, true, 0.9, "x"), 1).reasons())
        .containsExactly("ia_sigue_problema");
    assertThat(eval(good(), "REVISADO", new AiOpinion(true, true, false, false, false, true, 0.79, "x"), 1).reasons())
        .containsExactly("ia_baja_confianza");
    assertThat(eval(good(), "REVISADO", new AiOpinion(true, true, false, false, false, true, 0.80, "x"), 1).verdict())
        .isEqualTo("FUERTE");
    assertThat(eval(good(), "REVISADO", new AiOpinion(true, true, false, true, false, true, 0.9, "x"), 1).reasons())
        .containsExactly("ia_privacidad");
    assertThat(eval(good(), "REVISADO", new AiOpinion(true, true, false, false, true, true, 0.9, "x"), 1).reasons())
        .containsExactly("ia_privacidad");
    Result mala = eval(good(), "REVISADO", new AiOpinion(true, true, false, false, false, false, 0.9, "x"), 1);
    assertThat(mala.reasons()).containsExactly("foto_mala");
    assertThat(mala.verdict()).isEqualTo("DEBIL");
  }

  @Test
  void variosMotivosJuntos() {
    Result r = eval(new Facts(320.0, 10.0, REPORT.minusDays(1), REPORT, 3), "REVISADO",
        new AiOpinion(false, false, false, false, false, true, 0.5, "x"), 1);
    assertThat(r.verdict()).isEqualTo("DEBIL");
    assertThat(r.reasons()).containsExactly("lejos", "foto_vieja", "misma_foto", "ia_otro_lugar",
        "ia_sigue_problema", "ia_baja_confianza");
  }

  @Test
  void pendienteMientrasLaIANoTermino() {
    assertThat(eval(good(), null, null, 0).verdict()).isEqualTo("PENDIENTE");
    assertThat(eval(good(), "ERROR", null, 2).verdict()).isEqualTo("PENDIENTE");
  }

  @Test
  void sinIAOErrorAgotadoEsDebil() {
    Result sin = eval(good(), "SIN_IA", null, 0);
    assertThat(sin.verdict()).isEqualTo("DEBIL");
    assertThat(sin.reasons()).containsExactly("sin_ia");
    assertThat(eval(good(), "ERROR", null, 3).reasons()).containsExactly("sin_ia");
  }

  @Test
  void lineaDeTelegram() {
    assertThat(BarrioClosureEvidence.telegramLine("FUERTE", List.of(), 10.0, 0.91, true, true))
        .isEqualTo("Pruebas: FUERTES (IA 91 %: mismo lugar, ya no está)");
    assertThat(BarrioClosureEvidence.telegramLine("DEBIL", List.of("lejos", "foto_vieja", "sin_fecha"), 320.4, null, null, null))
        .isEqualTo("Pruebas: débiles — lejos (320 m), foto vieja");
  }
}
