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

  @Test
  void textoTelegramDeAutoPublicado() {
    assertThat(TelegramNotifyService.autoPublishedText(7L, "Bache", "Lagomar", 93, null, "https://x.test"))
        .isEqualTo("✅ Publicado por la IA (93 %) — Bache en Lagomar (reclamo #7). Si está mal: https://x.test/admin?tab=barrio → Despublicar");
    assertThat(TelegramNotifyService.autoPublishedText(8L, "Bache", "Lagomar", 90, 3L, "https://x.test"))
        .contains("(fusionado en #3)");
  }
}
