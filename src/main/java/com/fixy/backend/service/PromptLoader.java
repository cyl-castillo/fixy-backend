package com.fixy.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/**
 * Carga prompts de sistema desde {@code src/main/resources/prompts/} en vez de tenerlos
 * hardcodeados en el Java. Fail-fast: si el recurso falta o queda vacío, el bean que lo usa
 * no debe levantar en un estado a medias (mejor un arranque roto y visible que un prompt
 * vacío silencioso en producción).
 */
final class PromptLoader {

  private PromptLoader() {}

  static String load(String classpathLocation) {
    ClassPathResource resource = new ClassPathResource(classpathLocation);
    if (!resource.exists()) {
      throw new IllegalStateException("Prompt no encontrado en classpath: " + classpathLocation);
    }
    try (InputStream in = resource.getInputStream()) {
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (content.isBlank()) {
        throw new IllegalStateException("Prompt vacío en classpath: " + classpathLocation);
      }
      return content;
    } catch (IOException ex) {
      throw new IllegalStateException("No se pudo leer el prompt: " + classpathLocation, ex);
    }
  }
}
