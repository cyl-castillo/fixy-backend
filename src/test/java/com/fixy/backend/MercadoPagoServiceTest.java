package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.service.MercadoPagoService;
import org.junit.jupiter.api.Test;

/**
 * La notification_url de las preferences se arma desde
 * fixy.payments.webhook-base-url. Sin base configurada NO se manda (null),
 * porque una URL vacía o inválida hace que MP nunca notifique el pago.
 */
class MercadoPagoServiceTest {

  @Test
  void buildsNotificationUrlFromBase() {
    assertThat(MercadoPagoService.buildNotificationUrl("https://api.fixy.com.uy"))
        .isEqualTo("https://api.fixy.com.uy/api/webhooks/mercadopago");
  }

  @Test
  void stripsTrailingSlashesFromBase() {
    assertThat(MercadoPagoService.buildNotificationUrl("https://api.fixy.com.uy/"))
        .isEqualTo("https://api.fixy.com.uy/api/webhooks/mercadopago");
    assertThat(MercadoPagoService.buildNotificationUrl("https://api.fixy.com.uy//"))
        .isEqualTo("https://api.fixy.com.uy/api/webhooks/mercadopago");
  }

  @Test
  void returnsNullWhenBaseMissing() {
    assertThat(MercadoPagoService.buildNotificationUrl(null)).isNull();
    assertThat(MercadoPagoService.buildNotificationUrl("")).isNull();
    assertThat(MercadoPagoService.buildNotificationUrl("   ")).isNull();
  }
}
