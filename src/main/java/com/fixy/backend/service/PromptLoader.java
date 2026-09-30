package com.fixy.backend.service;

import com.fixy.backend.domain.DomainCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/**
 * Carga prompts de sistema desde {@code src/main/resources/prompts/} en vez de tenerlos
 * hardcodeados en el Java. Fail-fast: si el recurso falta o queda vacío, el bean que lo usa
 * no debe levantar en un estado a medias (mejor un arranque roto y visible que un prompt
 * vacío silencioso en producción).
 *
 * <p>Core Fase 2: los prompts son PLANTILLAS. Lo que deriva del dominio (zonas, categorías,
 * frase de negocio) son placeholders {@code {{a.b}}} que {@link PromptRenderer} rellena desde
 * {@link DomainCatalog}; {@link #load} devuelve el texto ya renderizado.
 */
final class PromptLoader {

  private PromptLoader() {}

  /** El prompt con sus placeholders rellenados desde el catálogo de dominio. */
  static String load(String classpathLocation) {
    return PromptRenderer.render(loadRaw(classpathLocation), DomainCatalog.get());
  }

  /**
   * Como {@link #load}, para plantillas que después pasan por {@code String.formatted(...)}
   * (conservan sus {@code %s}; los datos del catálogo se escapan).
   */
  static String loadFormatTemplate(String classpathLocation) {
    return PromptRenderer.renderForFormat(loadRaw(classpathLocation), DomainCatalog.get());
  }

  /** El archivo tal cual está en el classpath, con los placeholders sin rellenar. */
  static String loadRaw(String classpathLocation) {
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
