package com.fixy.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Provider;
import org.junit.jupiter.api.Test;

/** 2026-10-08: la confirmación del SÍ salía a {@code null} porque el técnico solo tenía
 * {@code phone}; el número de respuesta sigue el mismo criterio que el envío de la oferta. */
class WhatsAppWebhookProviderNumberTest {

  @Test
  void usaElWhatsappSiLoCargo() {
    Provider p = new Provider();
    p.setPhone("093640983");
    p.setWhatsappNumber("59893640983");
    assertThat(WhatsAppWebhookController.providerWhatsapp(p)).isEqualTo("59893640983");
  }

  @Test
  void caeAlTelefonoSiNoHayWhatsapp() {
    Provider p = new Provider();
    p.setPhone("093640983");
    assertThat(WhatsAppWebhookController.providerWhatsapp(p)).isEqualTo("093640983");
    p.setWhatsappNumber("   ");
    assertThat(WhatsAppWebhookController.providerWhatsapp(p)).isEqualTo("093640983");
  }
}
