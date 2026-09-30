package com.fixy.backend.model;

import com.fixy.backend.domain.DomainCatalog;
import com.fixy.backend.domain.ZoneDef;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Fuente única de verdad de qué zonas cubre Fixy y de cómo se contienen
 * entre sí. Análogo de {@link ServiceCategory}, y creado por la misma razón:
 * "qué zonas existen" vivía triplicado en {@code AgentService.CIUDAD_DE_LA_COSTA_ZONES},
 * {@code LeadAgentService.MVP_LOCATIONS} y {@code LeadService.MVP_LOCATIONS}, y
 * las tres copias YA se habían desincronizado.
 *
 * <p>Bug real que lo motivó (embudo de prod, 2026-08-03): "Montes de Solymar"
 * se agregó el 2026-07-16 a las listas de {@code AgentService} y
 * {@code LeadAgentService} pero NO a la de {@code LeadService}. Resultado: un
 * pedido en Montes de Solymar quedaba {@code readyForMatching} y matcheable
 * para el agente, pero {@code LeadService.computeBlockingFields} lo marcaba
 * {@code zona_fuera_de_cobertura} y {@code nextRecommendedAction =
 * out_of_coverage_area} — o sea, Fixy le decía al cliente que no llega a su
 * barrio mientras al mismo tiempo le buscaba proveedor ahí. Hay un pedido real
 * en esa zona en prod y Barométrica Nueva Era la declara en su cobertura.
 *
 * <h2>Jerarquía</h2>
 * "Ciudad de la Costa" NO es un barrio más: es el paraguas que contiene a
 * Solymar, Lagomar, El Pinar, Shangrilá y los demás. El matching las comparaba
 * como strings planos con igualdad exacta, así que el paraguas y sus barrios
 * no se veían entre sí en ninguna de las dos direcciones. Ver
 * {@link #covers(String, String)}.
 *
 * <p>Los alias existen porque el mismo barrio llega escrito de varias formas
 * (con y sin tilde, "san jose"/"san josé"). La normalización saca acentos, así
 * que acá solo hacen falta los alias que NO son diferencia de acento.
 *
 * <h2>Por qué los alias dejaron de ser teóricos (embudo de prod, 2026-08-07)</h2>
 * El javadoc prometía alias desde el principio, pero el enum no tenía dónde
 * ponerlos: la única flexibilidad real era la de acentos. Y la zona que más
 * usa el vecino para nombrar Ciudad de la Costa —"La Costa"— no es una
 * diferencia de acento, es otro nombre.
 *
 * <p>Costó demanda medible: el proveedor #16 (mandados, la categoría más
 * pedida del embudo — 9 de 12 toques de categoría y 10 de 18 pedidos reales
 * en 7 días) se autoregistró con {@code primaryZone = "La Costa"}. Como
 * {@code fromLabel("la costa")} daba vacío, {@link #covers(String, String)}
 * caía a igualdad exacta y el proveedor era invisible para TODO pedido de
 * Lagomar, Solymar o Lomas de Solymar. Los pedidos #230, #231, #240 y #244
 * quedaron {@code readyForMatching} sin un solo match, con el agente
 * prometiéndole al cliente "te aviso apenas alguien levante el pedido".
 * Nadie se enteró: ni el proveedor, ni ops — el matching no tiene forma de
 * avisar que una zona declarada no existe.
 *
 * <h2>Fachada desde Core Fase 2</h2>
 * Los DATOS (etiquetas, alias, jerarquía) y la lógica ya no viven acá: viven en
 * {@code domain/home-services.yml} y en {@link DomainCatalog} (ver
 * CORE_FASE2_CONTRATO.md). Este enum se conserva porque los tests referencian
 * sus constantes; cada constante conserva su nombre (= {@code id} de la zona en
 * el YAML) y todo lo demás delega al catálogo. El código de producción usa
 * {@link DomainCatalog}: una zona agregada en el YAML existe para el sistema
 * aunque no tenga constante acá.
 */
public enum CoverageZone {
  CIUDAD_DE_LA_COSTA,
  SOLYMAR,
  LAGOMAR,
  EL_PINAR,
  SHANGRILA,
  BARRA_DE_CARRASCO,
  PARQUE_MIRAMAR,
  SAN_JOSE_DE_CARRASCO,
  LOMAS_DE_SOLYMAR,
  COLINAS_DE_SOLYMAR,
  MONTES_DE_SOLYMAR,
  AEROPARQUE;

  static {
    // Si el YAML no trae una constante, fallar al arrancar con un mensaje claro: la fachada
    // no puede delegar en algo que no existe.
    DomainCatalog catalog = DomainCatalog.get();
    for (CoverageZone zone : values()) {
      if (catalog.zoneById(zone.name()).isEmpty()) {
        throw new IllegalStateException("CoverageZone." + zone.name() + " no existe en el catálogo de dominio ("
            + catalog.source() + "): agregala al YAML");
      }
    }
  }

  private ZoneDef def() {
    return DomainCatalog.get().zoneById(name()).orElseThrow();
  }

  /** Etiqueta humana canónica, tal como se le muestra al cliente. */
  public String label() {
    return def().label();
  }

  /** El paraguas que contiene a esta zona, o vacío si ella misma es el paraguas. */
  public Optional<CoverageZone> parent() {
    return def().parent().flatMap(CoverageZone::fromId);
  }

  private static Optional<CoverageZone> fromId(String id) {
    return Arrays.stream(values()).filter(zone -> zone.name().equals(id)).findFirst();
  }

  /**
   * Todas las etiquetas canónicas, en el orden del catálogo (el paraguas primero).
   * Lo consumen los catálogos que se le ofrecen al cliente y al proveedor.
   */
  public static final List<String> LABELS = DomainCatalog.get().zoneLabels();

  /**
   * Todas las formas normalizadas aceptadas (canónicas + alias sin acento).
   * Es el reemplazo de las tres copias de {@code MVP_LOCATIONS}: preguntar
   * "¿esta zona está dentro de la cobertura de Fixy?" es {@code
   * NORMALIZED_TOKENS.contains(normalize(zona))}.
   */
  public static final Set<String> NORMALIZED_TOKENS = DomainCatalog.get().normalizedTokens();

  /** Minúsculas y sin acentos. Delega en {@link DomainCatalog#normalize}. */
  public static String normalize(String value) {
    return DomainCatalog.normalize(value);
  }

  /**
   * La constante correspondiente a un texto libre (etiqueta o alias), si Fixy la cubre.
   * Una zona agregada solo en el YAML (sin constante) devuelve vacío acá: el código de
   * producción usa {@link DomainCatalog#zoneByLabel}, que sí la conoce.
   */
  public static Optional<CoverageZone> fromLabel(String value) {
    return DomainCatalog.get().zoneByLabel(value).flatMap(zone -> fromId(zone.id()));
  }

  /** Ver {@link DomainCatalog#unrecognized}. */
  public static List<String> unrecognized(String... declaredZones) {
    return DomainCatalog.get().unrecognized(declaredZones);
  }

  /** true si Fixy declara cobertura en esta zona (cualquier alias). */
  public static boolean isCovered(String value) {
    return DomainCatalog.get().isCovered(value);
  }

  /** Ver {@link DomainCatalog#covers}: la jerarquía del paraguas vale en las dos direcciones. */
  public static boolean covers(String providerZone, String leadZone) {
    return DomainCatalog.get().covers(providerZone, leadZone);
  }
}
