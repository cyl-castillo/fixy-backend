package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.service.BarrioAutoPublishPolicy.Decision;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BarrioAutoPublishPolicyTest {

  private static Decision d(boolean on, String verdict, Double conf, String status, String desc, long today) {
    return BarrioAutoPublishPolicy.decide(on, verdict, conf, 0.90, status, desc, today, 10);
  }

  @Test
  void publicaSoloSiSeCumpleTodo() {
    assertThat(d(true, "APROBAR", 0.93, "PENDING_REVIEW", "bache grande", 0)).isEqualTo(Decision.PUBLISH);
    assertThat(d(true, "APROBAR", 0.90, "PENDING_REVIEW", null, 9)).isEqualTo(Decision.PUBLISH); // borde inclusivo
  }

  @Test
  void interruptorApagadoNuncaPublica() {
    assertThat(d(false, "APROBAR", 0.99, "PENDING_REVIEW", null, 0)).isEqualTo(Decision.NO);
  }

  @Test
  void soloAprobar() {
    for (String v : new String[] {"DUDOSO", "ERROR", "SIN_IA", null}) {
      assertThat(d(true, v, 0.99, "PENDING_REVIEW", null, 0)).as(String.valueOf(v)).isEqualTo(Decision.NO);
    }
  }

  @Test
  void debajoDelUmbralNo() {
    assertThat(d(true, "APROBAR", 0.8999, "PENDING_REVIEW", null, 0)).isEqualTo(Decision.NO);
    assertThat(d(true, "APROBAR", null, "PENDING_REVIEW", null, 0)).isEqualTo(Decision.NO);
    assertThat(d(true, "APROBAR", Double.NaN, "PENDING_REVIEW", null, 0)).isEqualTo(Decision.NO);
  }

  @Test
  void debeSeguirPendiente() {
    for (String s : new String[] {"PUBLISHED", "REJECTED", "MERGED", "RESOLVED"}) {
      assertThat(d(true, "APROBAR", 0.99, s, null, 0)).as(s).isEqualTo(Decision.NO);
    }
  }

  @Test
  void smokeNoSePublica() {
    assertThat(d(true, "APROBAR", 0.99, "PENDING_REVIEW", "prueba [smoke] x", 0)).isEqualTo(Decision.NO);
  }

  @Test
  void topeDiario() {
    assertThat(d(true, "APROBAR", 0.99, "PENDING_REVIEW", null, 10)).isEqualTo(Decision.DAILY_CAP_REACHED);
    assertThat(d(true, "APROBAR", 0.99, "PENDING_REVIEW", null, 11)).isEqualTo(Decision.DAILY_CAP_REACHED);
    // por debajo del umbral gana NO sobre el tope (no se avisa "tope" de algo que no iba a publicarse)
    assertThat(d(true, "APROBAR", 0.5, "PENDING_REVIEW", null, 10)).isEqualTo(Decision.NO);
  }

  @Test
  void elDiaEsDeMontevideoNoUtc() {
    // 2026-10-28T02:30Z = 27/10 23:30 en Montevideo (UTC-3): todavía es el día 27.
    Instant now = Instant.parse("2026-10-28T02:30:00Z");
    assertThat(BarrioAutoPublishPolicy.dayStart(now)).isEqualTo(Instant.parse("2026-10-27T03:00:00Z"));
    assertThat(BarrioAutoPublishPolicy.dayEnd(now)).isEqualTo(Instant.parse("2026-10-28T03:00:00Z"));
    // 2026-10-28T03:00Z ya es el 28 local.
    assertThat(BarrioAutoPublishPolicy.dayStart(Instant.parse("2026-10-28T03:00:00Z")))
        .isEqualTo(Instant.parse("2026-10-28T03:00:00Z"));
  }
}
