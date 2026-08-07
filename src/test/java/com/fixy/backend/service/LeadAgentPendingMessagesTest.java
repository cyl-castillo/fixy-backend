package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Detección de categoría sobre la TANDA de mensajes pendientes de un turno
 * (smoke lead #236, 2026-08-07): dos mensajes seguidos del cliente antes de
 * que el agente contestara hacían que el turno clasificara solo por el
 * último ("agua enlatada" → plomería) ignorando el mandado del primero.
 * La regla replica la semántica secuencial: el primer mensaje que detecta
 * gana, y uno posterior solo pisa con corrección explícita.
 */
class LeadAgentPendingMessagesTest {

  @Test
  void smoke236_elPrimerMensajeGanaAunqueElSegundoTraigaKeywordsDeOtraCategoria() {
    assertThat(LeadAgentService.detectCategoryFromMessages(List.of(
        "Necesito un mandado: la compra del supermercado",
        "Hola, necesito comprar agua enlatada.")))
        .isEqualTo("mandados");
  }

  @Test
  void laCorreccionExplicitaEnUnMensajePosteriorSiPisaLaDeteccion() {
    assertThat(LeadAgentService.detectCategoryFromMessages(List.of(
        "Quiero una torta para un cumpleaños",
        "Perdón, me equivoqué: es jardinería, cortar el pasto")))
        .isEqualTo("jardineria");
  }

  @Test
  void unPrimerMensajeSinSenalNoBloqueaLaDeteccionDelSegundo() {
    assertThat(LeadAgentService.detectCategoryFromMessages(List.of(
        "Hola, buenas",
        "se me rompió una canilla y pierde agua")))
        .isEqualTo("plomeria");
  }

  @Test
  void sinDeteccionEnNingunMensajeDevuelveNull() {
    assertThat(LeadAgentService.detectCategoryFromMessages(List.of("Hola", "¿están?"))).isNull();
    assertThat(LeadAgentService.detectCategoryFromMessages(List.of())).isNull();
  }
}
