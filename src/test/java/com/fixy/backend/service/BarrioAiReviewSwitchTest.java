package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.BarrioReport;
import org.junit.jupiter.api.Test;

class BarrioAiReviewSwitchTest {

  @Test
  void interruptorApagadoNoTocaNada() {
    // repo/review null: si intentara algo, reventaría con NPE.
    assertThat(new BarrioAiReviewScheduler(null, null, false).runOnce()).isZero();
  }

  @Test
  void lineaDeTelegramSegunVeredicto() {
    BarrioReport r = new BarrioReport();
    r.setCategory("bache");
    r.setAiVerdict("APROBAR");
    r.setAiConfidence(0.9);
    assertThat(BarrioAiReviewService.telegramLine(r)).isEqualTo("IA: aprobar (90 %)");
    r.setAiVerdict("ERROR");
    assertThat(BarrioAiReviewService.telegramLine(r)).isEqualTo("IA: no pudo revisar");
    r.setAiVerdict("SIN_IA");
    assertThat(BarrioAiReviewService.telegramLine(r)).contains("sin revisión");
  }
}
