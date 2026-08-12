package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contrato del payload que arma {@code PushNotificationService.send}: el
 * deep-link (Fase Push-1, FIXY_OFERTAS_PUSH_Y_MAPA.md §3) viaja en la clave
 * {@code url}. Unit test puro (sin Spring, sin cifrado real) porque el
 * payload se cifra antes de salir por red — este es el único punto donde
 * se lo puede leer en texto plano.
 */
class PushNotificationServicePayloadTest {

  @Test
  void payloadMap_sinUrlExplicita_defaultDeLosTriggersExistentesSigueSiendoRaiz() {
    assertThat(PushNotificationService.payloadMap("titulo", "cuerpo", "/"))
        .containsEntry("url", "/")
        .containsEntry("title", "titulo")
        .containsEntry("body", "cuerpo");
  }

  @Test
  void payloadMap_conUrlExplicita_laRespetaSinTocarTituloYCuerpo() {
    assertThat(PushNotificationService.payloadMap("Ofertas de esta semana en Aeroparque", "20% off", "/ofertas"))
        .containsEntry("url", "/ofertas");
  }
}
