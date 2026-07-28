package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Mejora diaria 2026-07-28: 73 de 87 leads reales de julio quedaron sin
 * teléfono — cliente irrecuperable si cierra la pestaña. El agente pide el
 * WhatsApp UNA vez, determinista, recién cuando categoría y zona ya están
 * resueltas (regla en código, no en prompt — lección del 8B).
 */
class LeadAgentPhoneAskTest {

  @Test
  void pideElWhatsappSoloConCategoriaYZonaResueltasYSinTelefono() {
    assertThat(LeadAgentService.shouldAskContactPhone(
        true, true, null, false, "Perfecto, ya busco proveedor de plomería en Solymar."))
        .isTrue();
  }

  @Test
  void noPideMientrasFaltanCategoriaOZona() {
    // El pedido de teléfono nunca compite con las preguntas críticas del intake.
    assertThat(LeadAgentService.shouldAskContactPhone(
        false, true, null, false, "Contame qué necesitás.")).isFalse();
    assertThat(LeadAgentService.shouldAskContactPhone(
        true, false, null, false, "¿En qué zona estás?")).isFalse();
  }

  @Test
  void noPideSiYaHayTelefonoOLlegoEnEsteTurno() {
    assertThat(LeadAgentService.shouldAskContactPhone(
        true, true, "099123456", false, "Listo, te aviso.")).isFalse();
    assertThat(LeadAgentService.shouldAskContactPhone(
        true, true, null, true, "Anotado el número.")).isFalse();
  }

  @Test
  void noDuplicaSiLaRespuestaDelModeloYaLoPide() {
    assertThat(LeadAgentService.shouldAskContactPhone(
        true, true, null, false, "¿Me pasás un WhatsApp para coordinar?")).isFalse();
    // Detección insensible a acentos, igual que asksForZone.
    assertThat(LeadAgentService.asksForContactPhone("¿Me dejás tu teléfono?")).isTrue();
    assertThat(LeadAgentService.asksForContactPhone("dejame tu telefono")).isTrue();
    assertThat(LeadAgentService.asksForContactPhone("Ya contacto al proveedor.")).isFalse();
  }

  @Test
  void elTextoDelPedidoAclaraQueEsOpcional() {
    // La promesa del modelo es cero fricción: dejar el número nunca es requisito.
    assertThat(LeadAgentService.CONTACT_PHONE_ASK).contains("Si preferís, seguimos solo por acá");
    // Y el propio texto debe ser detectable por asksForContactPhone para que
    // contactPhoneAlreadyAsked lo encuentre y no se pida dos veces.
    assertThat(LeadAgentService.asksForContactPhone(LeadAgentService.CONTACT_PHONE_ASK)).isTrue();
  }
}
