package com.fixy.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Parseo del JSON crudo de la Responses API de OpenAI, sin red.
 *
 * <p>Contexto: desde al menos 2026-09-02 y hasta 2026-09-30 TODOS los turnos
 * del agente en prod (gpt-5-mini) caían a "fallback heurístico" sin WARN. El
 * parseo anterior solo miraba {@code output_text} en la raíz (atajo del SDK
 * que el HTTP crudo no trae) y {@code output[0]}; con modelos razonadores
 * {@code output[0]} es {@code type=reasoning} y el {@code message} está en
 * {@code output[1]}. Las formas de abajo replican una respuesta real
 * capturada desde prod el 2026-09-30 (solo estructura; sin ids ni key).
 */
class LlmGatewayResponsesParsingTest {

  private final LlmGateway gateway = new LlmGateway(
      new ObjectMapper(), "", "gpt-5-mini", true, "openai",
      "http://127.0.0.1:11434", "qwen2.5:3b", "", "", "cf-model");

  @Test
  void rootOutputTextShortcutStillWorks() throws Exception {
    String raw = "{\"status\":\"completed\",\"output_text\":\"  hola raíz  \",\"output\":[]}";
    assertEquals("hola raíz", gateway.parseResponsesBody(raw));
  }

  @Test
  void messageAsFirstOutputItem() throws Exception {
    String raw = "{\"status\":\"completed\",\"output\":[{"
        + "\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\","
        + "\"content\":[{\"type\":\"output_text\",\"annotations\":[],\"text\":\"hola gpt-4.1\"}]}]}";
    assertEquals("hola gpt-4.1", gateway.parseResponsesBody(raw));
  }

  @Test
  void reasoningItemBeforeMessageIsSkipped() throws Exception {
    // Forma real de gpt-5-mini: output[0]=reasoning (sin content), output[1]=message.
    String raw = "{\"status\":\"completed\",\"model\":\"gpt-5-mini-2025-08-07\",\"output\":["
        + "{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[]},"
        + "{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\","
        + "\"content\":[{\"type\":\"output_text\",\"annotations\":[],\"text\":\"{\\\"reply\\\":\\\"hola\\\"}\"}]}]}";
    assertEquals("{\"reply\":\"hola\"}", gateway.parseResponsesBody(raw));
  }

  @Test
  void picksFirstOutputTextPartAndIgnoresRefusalParts() throws Exception {
    String raw = "{\"status\":\"completed\",\"output\":["
        + "{\"type\":\"reasoning\",\"summary\":[]},"
        + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":["
        + "{\"type\":\"refusal\",\"refusal\":\"no\"},"
        + "{\"type\":\"output_text\",\"text\":\"primero\"},"
        + "{\"type\":\"output_text\",\"text\":\"segundo\"}]}]}";
    assertEquals("primero", gateway.parseResponsesBody(raw));
  }

  @Test
  void contentPartWithoutTypeButWithTextIsAccepted() throws Exception {
    // Tolerancia al formato viejo que aceptaba el parseo original (content[].text sin type).
    String raw = "{\"output\":[{\"type\":\"message\",\"content\":[{\"text\":\"legacy\"}]}]}";
    assertEquals("legacy", gateway.parseResponsesBody(raw));
  }

  @Test
  void onlyReasoningItemReturnsNull() throws Exception {
    // incomplete por max_output_tokens: el modelo gastó todo en razonar y no emitió message.
    String raw = "{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
        + "\"output\":[{\"type\":\"reasoning\",\"summary\":[]}]}";
    assertNull(gateway.parseResponsesBody(raw));
  }

  @Test
  void emptyOutputAndNoRootTextReturnsNull() throws Exception {
    assertNull(gateway.parseResponsesBody("{\"status\":\"completed\",\"output\":[]}"));
    assertNull(gateway.parseResponsesBody("{\"output_text\":\"   \",\"output\":[]}"));
  }
}
