package com.fixy.backend.model;

import com.fixy.backend.domain.CategoryDef;
import com.fixy.backend.domain.DomainCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Fuente única de verdad de qué categorías de servicio existen en Fixy.
 *
 * Antes de esto, "qué categorías existen" vivía fragmentado en ~5 lugares
 * (LeadAgentService.MVP_CATEGORIES + el enum del schema del turno,
 * LeadService.MVP_CATEGORIES, AgentService.intakeJsonSchema, y el prompt
 * lead-agent-system.md) que se podían desincronizar entre sí — ya pasó un
 * bug real al sumar pastelería (ver CURRENT_WORK.md / ARQUITECTURA_SUPERAPP.md
 * building block #5). Los puntos de arriba ahora DERIVAN de este enum.
 *
 * {@code id} es el valor persistido en {@code Lead.detectedCategory} (String
 * plano, sin @Enumerated — no vale la pena una migración de columna para esto).
 * {@code mvp} distingue las categorías que participan en matching real
 * (ProviderCatalogService, LeadService.computeBlockingFields) de categorías
 * legacy que el clasificador todavía reconoce por texto pero que Fixy no
 * cubre operativamente hoy (electricidad, cerrajería, reparaciones genéricas).
 * {@code keywords} son las palabras que activan la categoría en los
 * clasificadores heurísticos (fallback sin LLM); centralizadas acá para que
 * agregar una keyword nueva no requiera tocar 2 métodos distintos.
 *
 * <p><b>Fachada desde Core Fase 2</b> (ver CORE_FASE2_CONTRATO.md): los DATOS
 * (etiquetas, keywords, rangos de precio, guiones de intake) y la lógica de
 * detección viven ahora en {@code domain/home-services.yml} y en
 * {@link DomainCatalog}. Este enum se conserva porque los tests referencian
 * sus constantes; cada constante conserva su {@code id} y su nombre (=
 * {@code enumName} en el YAML) y todo lo demás delega al catálogo. El código de
 * producción usa {@link DomainCatalog}: una categoría agregada en el YAML existe
 * para el sistema aunque no tenga constante acá. Los rangos de precio son
 * placeholders que Carlos debe validar: se editan en el YAML.
 */
public enum ServiceCategory {
  PLOMERIA("plomeria"),
  ELECTRICIDAD("electricidad"),
  CERRAJERIA("cerrajeria"),
  BAROMETRICA("barometrica"),
  JARDINERIA("jardineria"),
  AIRES_ACONDICIONADOS("aires_acondicionados"),
  REPARACIONES("reparaciones"),
  PASTELERIA("pasteleria"),
  DECORACION_FIESTAS("decoracion_fiestas"),
  MANDADOS("mandados"),
  OTRO("otro");

  static {
    // Si el YAML no trae una constante (o su id no coincide), fallar al arrancar con un
    // mensaje claro: la fachada no puede delegar en algo que no existe.
    DomainCatalog catalog = DomainCatalog.get();
    for (ServiceCategory category : values()) {
      CategoryDef def = catalog.categoryByEnumName(category.name())
          .orElseThrow(() -> new IllegalStateException("ServiceCategory." + category.name()
              + " no existe en el catálogo de dominio (" + catalog.source() + "): agregala al YAML"));
      if (!def.id().equals(category.id)) {
        throw new IllegalStateException("ServiceCategory." + category.name() + " tiene id '" + category.id
            + "' pero el catálogo de dominio (" + catalog.source() + ") declara '" + def.id() + "'");
      }
    }
  }

  private final String id;

  ServiceCategory(String id) {
    this.id = id;
  }

  private CategoryDef def() {
    return DomainCatalog.get().categoryByEnumName(name()).orElseThrow();
  }

  /** Guion de intake para el agente (o null). Ver {@link CategoryDef#intakeHint()}. */
  public static String intakeHintForId(String rawId) {
    return DomainCatalog.get().intakeHintForId(rawId);
  }

  /** Valor persistido en Lead.detectedCategory / IntakeRequest.serviceCategory. */
  public String id() {
    return id;
  }

  /** Nombre en español para mostrarle al cliente (con tildes). */
  public String label() {
    return def().label();
  }

  /** true si participa en matching real (cobertura MVP actual de Fixy). */
  public boolean isMvp() {
    return def().mvp();
  }

  public List<String> keywords() {
    return def().keywords();
  }

  /** true si hay un rango de precio orientativo cargado para esta categoría. */
  public boolean hasPriceRange() {
    return def().hasPriceRange();
  }

  /** Rango orientativo en UYU, formato "$min–max", o null si no hay rango cargado. */
  public String priceRangeLabel() {
    return def().priceRangeLabel();
  }

  /** IDs de las categorías MVP (matching real) — reemplaza los MVP_CATEGORIES duplicados. */
  public static final List<String> MVP_IDS = DomainCatalog.get().mvpIds();

  /**
   * Nombres en español de las categorías MVP, para decirle al vecino qué
   * consigue Fixy HOY. Deriva del catálogo a propósito (mismo criterio que
   * {@link CoverageZone#LABELS}): sumar una categoría no puede dejar el
   * mensaje mintiéndole a la gente, que es el bug que motivó este enum.
   */
  public static final List<String> MVP_LABELS = DomainCatalog.get().mvpLabels();

  /** Todos los IDs conocidos, incluyendo "otro" — para el enum del JSON schema del clasificador. */
  public static final List<String> ALL_IDS_INCLUDING_OTRO = DomainCatalog.get().allCategoryIds();

  /**
   * La constante para un id. Una categoría agregada solo en el YAML (sin constante) devuelve
   * vacío acá: el código de producción usa {@link DomainCatalog#categoryById}, que sí la conoce.
   */
  public static Optional<ServiceCategory> fromId(String rawId) {
    return DomainCatalog.get().categoryById(rawId).flatMap(ServiceCategory::fromEnumName);
  }

  private static Optional<ServiceCategory> fromEnumName(CategoryDef def) {
    return Arrays.stream(values()).filter(c -> c.name().equals(def.enumName())).findFirst();
  }

  /** Ver {@link DomainCatalog#detectCategory}: keywords en orden de declaración + desempate. */
  public static Optional<ServiceCategory> detectFromText(String text) {
    return DomainCatalog.get().detectCategory(text).flatMap(ServiceCategory::fromEnumName);
  }

  /** Ver {@link DomainCatalog#refineCategoryId}. */
  public static String refineCategoryId(String text, String categoryId) {
    return DomainCatalog.get().refineCategoryId(text, categoryId);
  }

  /** Nombre humano en español para mostrarle al cliente; "tu pedido" si no se reconoce el id. */
  public static String humanLabel(String rawId) {
    return DomainCatalog.get().humanLabel(rawId);
  }

  /** Rango orientativo en UYU para el id dado, o null si no hay categoría o no hay rango cargado. */
  public static String priceRangeLabelForId(String rawId) {
    return DomainCatalog.get().priceRangeLabel(rawId);
  }
}
