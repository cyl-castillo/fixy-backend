package com.fixy.backend.service;

import com.fixy.backend.domain.CategoryDef;
import com.fixy.backend.domain.DomainCatalog;
import com.fixy.backend.domain.ZoneDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core Fase 2 (ver CORE_FASE2_CONTRATO.md): rellena los placeholders {@code {{a.b}}} de los
 * prompts de {@code src/main/resources/prompts/} con datos del {@link DomainCatalog}. Sintaxis
 * simple, sin librería: el dominio (zonas, categorías) deja de estar escrito a mano dentro del
 * texto de los prompts, donde se desincronizaba de las listas del código (el "Montes de
 * Solymar" que faltaba en las listas de los prompts mientras el schema sí lo tenía).
 *
 * <p>Todo lo que es texto fijo de reglas (tono, escalamiento) queda tal cual en el archivo del
 * prompt; solo lo derivado del dominio es placeholder. Un placeholder desconocido es un error
 * de arranque, no un texto raro en producción.
 *
 * <p>Placeholders: {@code business.name}, {@code business.intro}, {@code business.region},
 * {@code services.covered}, {@code zones.covered}, {@code zones.area_values},
 * {@code categories.count}, {@code categories.classifier_lines}, {@code categories.notes}.
 */
final class PromptRenderer {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

  private PromptRenderer() {}

  /** Rellena los placeholders del template con el catálogo dado. */
  static String render(String template, DomainCatalog catalog) {
    return render(template, catalog, false);
  }

  /**
   * Igual que {@link #render(String, DomainCatalog)} pero escapa los {@code %} de los VALORES
   * sustituidos ({@code %%}), para templates que después pasan por {@code String.formatted(...)}
   * (el clasificador de intake): un {@code %} en un dato del catálogo no puede romper el formato.
   */
  static String renderForFormat(String template, DomainCatalog catalog) {
    return render(template, catalog, true);
  }

  private static String render(String template, DomainCatalog catalog, boolean escapePercent) {
    Map<String, String> variables = variables(catalog);
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String name = matcher.group(1);
      String value = variables.get(name);
      if (value == null) {
        throw new IllegalStateException("Placeholder de prompt desconocido: {{" + name + "}} (válidos: "
            + variables.keySet() + ")");
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(escapePercent ? value.replace("%", "%%") : value));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** Los placeholders disponibles y su valor para este catálogo. */
  static Map<String, String> variables(DomainCatalog catalog) {
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("business.name", catalog.business().name());
    vars.put("business.intro", catalog.business().intro());
    vars.put("business.region", catalog.business().region());
    vars.put("services.covered", servicesCovered(catalog));
    vars.put("zones.covered", zonesCovered(catalog));
    vars.put("zones.area_values", zonesCovered(catalog) + ", " + catalog.areaUnknown());
    vars.put("categories.count", String.valueOf(
        catalog.categories().stream().filter(c -> !c.catchAll()).count()));
    vars.put("categories.classifier_lines", classifierLines(catalog));
    vars.put("categories.notes", categoryNotes(catalog));
    return vars;
  }

  /** "plomería, barométrica (nota) y mandados y trámites (nota)": las categorías MVP con su aclaración. */
  private static String servicesCovered(DomainCatalog catalog) {
    List<String> items = catalog.mvpCategories().stream()
        .map(c -> c.coverageNote() == null ? c.label() : c.label() + " (" + c.coverageNote() + ")")
        .toList();
    return items.size() < 2
        ? String.join(", ", items)
        : String.join(", ", items.subList(0, items.size() - 1)) + " y " + items.get(items.size() - 1);
  }

  /** Las zonas en el orden de los prompts: específicas en su orden y el paraguas después. */
  private static String zonesCovered(DomainCatalog catalog) {
    return String.join(", ", catalog.promptZones().stream().map(ZoneDef::label).toList());
  }

  /** Una línea "- id: descripción" por categoría, en el orden del catálogo (incluida "otro"). */
  private static String classifierLines(DomainCatalog catalog) {
    return String.join("\n", catalog.categories().stream()
        .filter(c -> c.classifierDescription() != null)
        .map(c -> "- " + c.id() + ": " + c.classifierDescription())
        .toList());
  }

  /** Los párrafos por categoría (pastelería, mandados...), separados por una línea en blanco. */
  private static String categoryNotes(DomainCatalog catalog) {
    return String.join("\n\n", catalog.categories().stream()
        .map(CategoryDef::promptNotes)
        .filter(n -> n != null && !n.isBlank())
        .toList());
  }
}
