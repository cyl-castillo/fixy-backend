package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Payload exacto de {@code type=template} del Cloud API para la plantilla de
 * vecinos {@code customer_order_update} (2026-10-13): body con dos parámetros
 * y, opcionalmente, el botón URL "Ver mi pedido" con sufijo dinámico.
 */
class WhatsAppTemplatePayloadTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode build(List<String> body, String suffix) {
    return mapper.valueToTree(WhatsAppService.buildTemplateBody(
        "59893640983", "customer_order_update", "es", body, suffix));
  }

  @Test
  void conBotonUrl_agregaElComponenteButtonConElSufijo() throws Exception {
    JsonNode json = build(
        List.of("Melissa", "aceptó tu pedido y te confirma día y hora por acá."),
        "280/69f218c870ad4cc7bb50d1f6b4f01c8c");

    JsonNode expected = mapper.readTree("""
        {
          "messaging_product": "whatsapp",
          "to": "59893640983",
          "type": "template",
          "template": {
            "name": "customer_order_update",
            "language": { "code": "es" },
            "components": [
              { "type": "body", "parameters": [
                  { "type": "text", "text": "Melissa" },
                  { "type": "text", "text": "aceptó tu pedido y te confirma día y hora por acá." } ] },
              { "type": "button", "sub_type": "url", "index": "0", "parameters": [
                  { "type": "text", "text": "280/69f218c870ad4cc7bb50d1f6b4f01c8c" } ] }
            ]
          }
        }
        """);
    assertThat(json).isEqualTo(expected);
  }

  @Test
  void sinBotonUrl_soloVaElBody() {
    for (String noSuffix : new String[] {null, "", "  "}) {
      JsonNode components = build(List.of("Melissa", "va en camino."), noSuffix)
          .path("template").path("components");
      assertThat(components).hasSize(1);
      assertThat(components.get(0).path("type").asText()).isEqualTo("body");
      assertThat(components.get(0).path("parameters")).hasSize(2);
    }
  }

  @Test
  void sinParametrosNiBoton_noHayComponents() {
    JsonNode template = build(List.of(), null).path("template");
    assertThat(template.has("components")).isFalse();
    assertThat(template.path("name").asText()).isEqualTo("customer_order_update");
  }

  @Test
  void sinIdiomaUsaEsYSoloBodyCuandoNoHayBoton() {
    JsonNode legacy = mapper.valueToTree(WhatsAppService.buildTemplateBody(
        "59893640983", "provider_lead_offer", null, List.of("a", "b"), null));
    assertThat(legacy.path("template").path("language").path("code").asText()).isEqualTo("es");
    assertThat(legacy.path("template").path("components")).hasSize(1);
  }
}
