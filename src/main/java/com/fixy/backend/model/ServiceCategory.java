package com.fixy.backend.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
 */
public enum ServiceCategory {
  // ---------------------------------------------------------------------
  // RANGOS DE PRECIO (UYU) — Cotización Estimada, Ola 2 MVP.
  // Los dos últimos parámetros de cada constante son (precioMinimo, precioMaximo)
  // en pesos uruguayos, o (null, null) si todavía no hay rango cargado.
  // SON PLACEHOLDERS razonables para Uruguay 2026, puestos por ingeniería para
  // destrabar el feature — CARLOS DEBE VALIDARLOS con proveedores reales antes
  // de confiar en ellos frente a clientes. Para editar: cambiar los dos números
  // de la categoría correspondiente acá abajo y recompilar/redeployar (no hay
  // config externa hoy — si esto empieza a cambiar seguido, mover a application.yml).
  // Representan "visita + trabajo simple/estándar", no casos grandes o excepcionales;
  // el agente SIEMPRE aclara que el precio final lo confirma el proveedor.
  // ---------------------------------------------------------------------
  PLOMERIA("plomeria", "plomería", true,
      List.of("agua", "canilla", "ducha", "caño", "cano", "perdida", "pierde", "perdida de agua",
          "pierde agua", "plomer", "destap"),
      800, 2500,
      "qué pasa exactamente (pérdida, canilla tapada, destape) y si es urgente"),
  ELECTRICIDAD("electricidad", "electricidad", false,
      List.of("luz", "corriente", "enchufe", "tablero", "corto", "electric"),
      null, null,
      "qué falla (sin luz general, un enchufe, el tablero) y si es urgente"),
  CERRAJERIA("cerrajeria", "cerrajería", false,
      List.of("llave", "cerradura", "tranca", "me quede afuera", "me quede fuera", "cerraj"),
      null, null,
      "si quedó afuera AHORA (urgente) o es cambio/arreglo de cerradura"),
  BAROMETRICA("barometrica", "barométrica", true,
      List.of("pozo", "barometr", "camara septica", "cámara séptica"),
      1500, 4000,
      "si el pozo está desbordando (urgente) o es mantenimiento programado"),
  JARDINERIA("jardineria", "jardinería", true,
      List.of("jardin", "jardín", "pasto", "cesped", "césped", "cortar el pasto", "cortar pasto",
          "mantenimiento exterior", "jardiner"),
      1200, 3000,
      "tamaño aproximado del jardín y qué trabajo necesita (corte, limpieza, poda)"),
  AIRES_ACONDICIONADOS("aires_acondicionados", "aire acondicionado", true,
      List.of("aire acondicionado", "aires acondicionados", "aire que no enfria", "aire que no enfría",
          "split", "climatizacion", "climatización", "recarga de gas", "no enfria", "no enfría",
          "no calienta", "mantenimiento de aire", "refrigeracion", "refrigeración"),
      1500, 4500,
      "si es instalación, service/limpieza o reparación (no enfría/no calienta), y qué equipo es (split, ventana)"),
  REPARACIONES("reparaciones", "reparaciones", false,
      List.of("arreglo", "reparacion", "reparación", "hogar", "mueble", "persiana"),
      null, null,
      "qué hay que arreglar y de qué se trata (mueble, persiana, pared)"),
  PASTELERIA("pasteleria", "pastelería", true,
      List.of("torta", "tortas", "cumpleaños", "cumpleanos", "cumple ", "mesa dulce", "cupcake",
          "pasteleria", "pastelería", "reposteria", "repostería", "postre", "postres",
          "shots dulces", "candy bar"),
      900, 3500,
      "para cuándo lo necesita, para cuántas personas y qué tipo de torta o postre"),
  OTRO("otro", "otro", false, List.of(), null, null, null);

  /**
   * Superset de keywords por categoría, usado por los clasificadores heurísticos
   * "laxos" (detectan categoría desde texto libre sin categoría previa: antes
   * duplicado casi idéntico entre AgentService.detectService y
   * LeadAgentService.heuristicCategory). AgentService.normalizeServiceCategory
   * usa una lista deliberadamente más CORTA porque opera sobre una categoría ya
   * declarada por el usuario (no texto libre) — ese método NO deriva de acá a
   * propósito, para no ampliar sus matches y arriesgar falsos positivos.
   */

  private final String id;
  private final String label;
  private final boolean mvp;
  private final List<String> keywords;
  private final Integer priceMin;
  private final Integer priceMax;
  /** Qué le conviene preguntar el agente para ESTE servicio una vez que
   * categoría y zona están definidas. Guion de intake determinista: el 8B
   * demostró (lead #123, 2026-07-16) que sin esto inventa preguntas de otra
   * categoría ("¿cuántas porciones?" para un aire acondicionado). null =
   * sin guion, el agente pregunta genérico. */
  private final String intakeHint;

  ServiceCategory(String id, String label, boolean mvp, List<String> keywords,
      Integer priceMin, Integer priceMax, String intakeHint) {
    this.id = id;
    this.label = label;
    this.mvp = mvp;
    this.keywords = keywords;
    this.priceMin = priceMin;
    this.priceMax = priceMax;
    this.intakeHint = intakeHint;
  }

  /** Guion de intake para el agente (o null). Ver campo intakeHint. */
  public static String intakeHintForId(String rawId) {
    return fromId(rawId).map(c -> c.intakeHint).orElse(null);
  }

  /** Valor persistido en Lead.detectedCategory / IntakeRequest.serviceCategory. */
  public String id() {
    return id;
  }

  /** Nombre en español para mostrarle al cliente (con tildes). */
  public String label() {
    return label;
  }

  /** true si participa en matching real (cobertura MVP actual de Fixy). */
  public boolean isMvp() {
    return mvp;
  }

  public List<String> keywords() {
    return keywords;
  }

  /** true si hay un rango de precio orientativo cargado para esta categoría. */
  public boolean hasPriceRange() {
    return priceMin != null && priceMax != null;
  }

  /**
   * Rango orientativo en UYU, formato "$min–max", o null si no hay rango
   * cargado (ver comentario de placeholders arriba del enum). Los llamadores
   * deben chequear {@link #hasPriceRange()} antes de usar esto, y siempre
   * mostrarlo con el disclaimer "el precio final lo confirma el proveedor" —
   * nunca como precio exacto o final.
   */
  public String priceRangeLabel() {
    if (!hasPriceRange()) {
      return null;
    }
    return "$" + priceMin + "–" + priceMax;
  }

  /** IDs de las categorías MVP (matching real) — reemplaza los MVP_CATEGORIES duplicados. */
  public static final List<String> MVP_IDS = Arrays.stream(values())
      .filter(ServiceCategory::isMvp)
      .map(ServiceCategory::id)
      .toList();

  /** Todos los IDs conocidos, incluyendo "otro" — para el enum del JSON schema del clasificador. */
  public static final List<String> ALL_IDS_INCLUDING_OTRO = Arrays.stream(values())
      .map(ServiceCategory::id)
      .toList();

  public static Optional<ServiceCategory> fromId(String rawId) {
    if (rawId == null || rawId.isBlank()) {
      return Optional.empty();
    }
    String normalized = rawId.toLowerCase(Locale.ROOT).trim();
    return Arrays.stream(values()).filter(c -> c.id.equals(normalized)).findFirst();
  }

  /**
   * Detecta la categoría buscando keywords en un texto libre (mensaje del
   * cliente, problema del lead). Devuelve empty si ninguna keyword matchea —
   * los llamadores deciden si eso es "otro" o "sin detectar" según su flujo.
   * OTRO nunca matchea (no tiene keywords) — es siempre el default explícito.
   */
  public static Optional<ServiceCategory> detectFromText(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    String normalized = text.toLowerCase(Locale.ROOT);
    for (ServiceCategory category : values()) {
      for (String keyword : category.keywords) {
        if (normalized.contains(keyword)) {
          return Optional.of(category);
        }
      }
    }
    return Optional.empty();
  }

  /** Nombre humano en español para mostrarle al cliente; "tu pedido" si no se reconoce el id. */
  public static String humanLabel(String rawId) {
    return fromId(rawId).map(ServiceCategory::label)
        .orElse(rawId == null || rawId.isBlank() ? "tu pedido" : rawId);
  }

  /** Rango orientativo en UYU para el id dado, o null si no hay categoría o no hay rango cargado. */
  public static String priceRangeLabelForId(String rawId) {
    return fromId(rawId).map(ServiceCategory::priceRangeLabel).orElse(null);
  }
}
