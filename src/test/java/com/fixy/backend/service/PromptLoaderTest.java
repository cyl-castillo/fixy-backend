package com.fixy.backend.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Cubre la pieza (b) de la modernización del cerebro IA: los system prompts salieron del
 * Java hardcodeado a src/main/resources/prompts/ y se cargan al construir el bean.
 * Estos tests verifican tanto el fail-fast (recurso ausente/vacío) como que el prompt real
 * que usa LeadAgentService en producción sigue siendo el mismo contenido reconocible
 * (no se admite cambio de contenido en esta tanda, solo de ubicación).
 */
class PromptLoaderTest {

  @Test
  void loadsLeadAgentSystemPromptFromClasspath() {
    String prompt = PromptLoader.load("prompts/lead-agent-system.md");

    assertTrue(!prompt.isBlank(), "el prompt no debe estar vacío");
    assertTrue(prompt.contains("ESPAÑOL RIOPLATENSE"), "debe contener el marcador de idioma rioplatense");
    assertTrue(prompt.contains("Sos Fixy"), "debe contener la frase distintiva de identidad del agente");
  }

  @Test
  void loadsIntakeClassifierPromptFromClasspath() {
    String prompt = PromptLoader.load("prompts/intake-classifier.md");

    assertTrue(!prompt.isBlank(), "el prompt no debe estar vacío");
    assertTrue(prompt.contains("agente de intake de Fixy"), "debe contener la frase distintiva del rol");
    assertTrue(prompt.contains("%s"), "debe conservar los placeholders para formatted(...)");
  }

  @Test
  void missingResourceFailsFast() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> PromptLoader.load("prompts/no-existe.md"));
    assertTrue(ex.getMessage().contains("no-existe.md"));
  }

  @Test
  void blankResourceFailsFast() {
    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> PromptLoader.load("prompts/blank-for-test.md"));
    assertTrue(ex.getMessage().contains("vacío"));
  }
}
