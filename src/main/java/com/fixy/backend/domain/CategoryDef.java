package com.fixy.backend.domain;

import java.util.List;

/**
 * Una categoría de servicio, tal como la declara {@code domain/home-services.yml}.
 * Core Fase 2 (ver CORE_FASE2_CONTRATO.md): datos puros; la lógica
 * (detección por keywords, desempate, etiquetas) vive en {@link DomainCatalog}.
 *
 * @param id                    valor persistido en {@code Lead.detectedCategory} (String plano).
 * @param enumName              nombre de la constante de la fachada {@code ServiceCategory}.
 * @param label                 nombre en español para mostrarle al cliente (con tildes).
 * @param mvp                   participa en matching real (cobertura MVP actual de Fixy).
 * @param catchAll              es la categoría "otro": nunca matchea por keywords.
 * @param keywords              palabras que la activan en los clasificadores heurísticos (orden = comportamiento).
 * @param declaredKeywords      lista más corta para una categoría ya declarada por el usuario
 *                              ({@code AgentService.normalizeServiceCategory}); vacía = no participa.
 * @param priceMin              piso del rango orientativo en UYU, o {@code null} si no hay rango.
 * @param priceMax              techo del rango orientativo en UYU, o {@code null} si no hay rango.
 * @param intakeHint            guion de intake para el agente, o {@code null}.
 * @param classifierDescription línea de la categoría en el prompt del clasificador de intake.
 * @param coverageNote          aclaración entre paréntesis en "Servicios que cubrimos", o {@code null}.
 * @param promptNotes           párrafo por categoría del prompt del turno, o {@code null}.
 * @param menuDescription       descripción de la fila del menú de WhatsApp, o {@code null}.
 * @param ambiguity             desempate determinista contra otra categoría, o {@code null}.
 */
public record CategoryDef(
    String id,
    String enumName,
    String label,
    boolean mvp,
    boolean catchAll,
    List<String> keywords,
    List<String> declaredKeywords,
    Integer priceMin,
    Integer priceMax,
    String intakeHint,
    String classifierDescription,
    String coverageNote,
    String promptNotes,
    String menuDescription,
    Ambiguity ambiguity
) {

  /**
   * Desempate entre esta categoría y {@code alternativeId}: si el texto trae alguna de
   * {@code alternativeSignals} y NINGUNA de {@code keepSignals}, gana la alternativa.
   */
  public record Ambiguity(String alternativeId, List<String> alternativeSignals, List<String> keepSignals) {
    public Ambiguity {
      alternativeSignals = List.copyOf(alternativeSignals);
      keepSignals = List.copyOf(keepSignals);
    }
  }

  public CategoryDef {
    keywords = List.copyOf(keywords);
    declaredKeywords = List.copyOf(declaredKeywords);
  }

  /** true si hay un rango de precio orientativo cargado para esta categoría. */
  public boolean hasPriceRange() {
    return priceMin != null && priceMax != null;
  }

  /**
   * Rango orientativo en UYU, formato "$min–max", o null si no hay rango
   * cargado. Los llamadores deben chequear {@link #hasPriceRange()} antes de
   * usar esto, y siempre mostrarlo con el disclaimer "el precio final lo
   * confirma el proveedor" — nunca como precio exacto o final.
   */
  public String priceRangeLabel() {
    if (!hasPriceRange()) {
      return null;
    }
    return "$" + priceMin + "–" + priceMax;
  }
}
