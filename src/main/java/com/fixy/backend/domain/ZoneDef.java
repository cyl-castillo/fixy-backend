package com.fixy.backend.domain;

import java.util.List;
import java.util.Optional;

/**
 * Una zona de cobertura, tal como la declara {@code domain/home-services.yml}.
 * Core Fase 2 (ver CORE_FASE2_CONTRATO.md): datos puros; la lógica
 * (covers/isCovered/detección) vive en {@link DomainCatalog}.
 *
 * @param id             nombre estable de la zona (= nombre de la constante de la
 *                       fachada {@code CoverageZone}, ej. {@code LOMAS_DE_SOLYMAR}).
 * @param label          etiqueta humana canónica, tal como se le muestra al cliente.
 * @param parentId       id del paraguas que la contiene, o {@code null} si ella es el paraguas.
 * @param aliases        otros nombres de la misma zona que no son diferencia de acento.
 * @param detectPriority orden de evaluación de {@link DomainCatalog#detectZone} (menor primero).
 * @param detectTokens   fragmentos normalizados (sin acentos, minúscula) con que el cliente
 *                       nombra la zona en texto libre.
 */
public record ZoneDef(
    String id,
    String label,
    String parentId,
    List<String> aliases,
    int detectPriority,
    List<String> detectTokens
) {

  public ZoneDef {
    aliases = List.copyOf(aliases);
    detectTokens = List.copyOf(detectTokens);
  }

  /** El paraguas que contiene a esta zona, o vacío si ella misma es el paraguas. */
  public Optional<String> parent() {
    return Optional.ofNullable(parentId);
  }

  /** true si esta zona es un paraguas (no tiene padre). */
  public boolean isUmbrella() {
    return parentId == null;
  }

  /** true si el texto ya normalizado es la etiqueta o algún alias de esta zona. */
  boolean matches(String normalized) {
    if (DomainCatalog.normalize(label).equals(normalized)) {
      return true;
    }
    return aliases.stream().anyMatch(alias -> DomainCatalog.normalize(alias).equals(normalized));
  }
}
