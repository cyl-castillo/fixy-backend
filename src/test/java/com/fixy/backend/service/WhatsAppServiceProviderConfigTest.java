package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit puro (sin red, sin Spring): confirma que el default sigue siendo Meta
 * Cloud API sin ningun cambio de config (regresion cero para el flujo que ya
 * corre en prod), y que un BSP como 360dialog puede pisar path y esquema de
 * auth sin tocar el resto de WhatsAppService (sendText/sendTemplate/
 * sendInteractiveList comparten el mismo post()).
 */
class WhatsAppServiceProviderConfigTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void defaultConfigBuildsMetaPathAndBearerHeader() {
    WhatsAppService service = new WhatsAppService(
        MAPPER, "123456789", "meta-token", "v21.0",
        "https://graph.facebook.com", "bearer", "");

    assertThat(service.messagesPath()).isEqualTo("/v21.0/123456789/messages");
    assertThat(service.authHeader()).containsExactly("Authorization", "Bearer meta-token");
  }

  @Test
  void dialog360ConfigOverridesPathAndUsesApiKeyHeader() {
    WhatsAppService service = new WhatsAppService(
        MAPPER, "unused-with-360dialog", "fake-sandbox-key-for-test", "v21.0",
        "https://waba-sandbox.360dialog.io", "d360-api-key", "/v1/messages");

    assertThat(service.messagesPath()).isEqualTo("/v1/messages");
    assertThat(service.authHeader()).containsExactly("D360-API-KEY", "fake-sandbox-key-for-test");
  }

  @Test
  void blankMessagesPathFallsBackToMetaFormatEvenWithNonDefaultAuthScheme() {
    // Un BSP que reusa el formato de path de Meta pero otro header de auth
    // no deberia necesitar setear messages-path explicitamente.
    WhatsAppService service = new WhatsAppService(
        MAPPER, "999", "token", "v20.0", "https://example-bsp.io", "d360-api-key", "  ");

    assertThat(service.messagesPath()).isEqualTo("/v20.0/999/messages");
  }

  @Test
  void unknownAuthSchemeFallsBackToBearer() {
    WhatsAppService service = new WhatsAppService(
        MAPPER, "999", "token", "v21.0", "https://graph.facebook.com", "unknown-scheme", "");

    assertThat(service.authHeader()).containsExactly("Authorization", "Bearer token");
  }
}
