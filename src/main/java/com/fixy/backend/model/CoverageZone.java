package com.fixy.backend.model;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 */
public enum CoverageZone {
  /**
   * El paraguas. Contiene a todas las demás. "La Costa" es como le dice el
   * vecino en la calle, y es lo que un proveedor escribe cuando el campo es
   * texto libre.
   */
  CIUDAD_DE_LA_COSTA("Ciudad de la Costa", null, "La Costa"),

  SOLYMAR("Solymar", CIUDAD_DE_LA_COSTA),
  LAGOMAR("Lagomar", CIUDAD_DE_LA_COSTA),
  EL_PINAR("El Pinar", CIUDAD_DE_LA_COSTA),
  SHANGRILA("Shangrilá", CIUDAD_DE_LA_COSTA),
  BARRA_DE_CARRASCO("Barra de Carrasco", CIUDAD_DE_LA_COSTA),
  PARQUE_MIRAMAR("Parque Miramar", CIUDAD_DE_LA_COSTA),
  SAN_JOSE_DE_CARRASCO("San José de Carrasco", CIUDAD_DE_LA_COSTA),
  LOMAS_DE_SOLYMAR("Lomas de Solymar", CIUDAD_DE_LA_COSTA),
  COLINAS_DE_SOLYMAR("Colinas de Solymar", CIUDAD_DE_LA_COSTA),
  MONTES_DE_SOLYMAR("Montes de Solymar", CIUDAD_DE_LA_COSTA),
  AEROPARQUE("Aeroparque", CIUDAD_DE_LA_COSTA);

  private final String label;
  private final CoverageZone parent;
  private final List<String> aliases;

  CoverageZone(String label, CoverageZone parent, String... aliases) {
    this.label = label;
    this.parent = parent;
    this.aliases = List.of(aliases);
  }

  /** Etiqueta humana canónica, tal como se le muestra al cliente. */
  public String label() {
    return label;
  }

  /** El paraguas que contiene a esta zona, o vacío si ella misma es el paraguas. */
  public Optional<CoverageZone> parent() {
    return Optional.ofNullable(parent);
  }

  /**
   * Todas las etiquetas canónicas, en el orden del enum (el paraguas primero).
   * Lo consumen los catálogos que se le ofrecen al cliente y al proveedor.
   */
  public static final List<String> LABELS =
      Arrays.stream(values()).map(CoverageZone::label).toList();

  /**
   * Todas las formas normalizadas aceptadas (canónicas + alias sin acento).
   * Es el reemplazo de las tres copias de {@code MVP_LOCATIONS}: preguntar
   * "¿esta zona está dentro de la cobertura de Fixy?" es {@code
   * NORMALIZED_TOKENS.contains(normalize(zona))}.
   */
  public static final Set<String> NORMALIZED_TOKENS = buildTokens();

  private static Set<String> buildTokens() {
    Set<String> tokens = new LinkedHashSet<>();
    for (CoverageZone zone : values()) {
      tokens.add(normalize(zone.label));
      for (String alias : zone.aliases) {
        tokens.add(normalize(alias));
      }
    }
    return Set.copyOf(tokens);
  }

  /**
   * Minúsculas y sin acentos. Misma regla que usa
   * {@code ProviderCatalogService}: "Shangrilá" (como lo escribe el cliente)
   * tiene que ser el mismo token que "Shangrila" (como quedó registrada
   * Melissa en prod).
   */
  public static String normalize(String value) {
    if (value == null) {
      return "";
    }
    String lowered = value.trim().toLowerCase(Locale.ROOT);
    return java.text.Normalizer.normalize(lowered, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
  }

  /**
   * La zona canónica correspondiente a un texto libre, si Fixy la cubre —
   * por su etiqueta o por cualquiera de sus alias. Devolver la canónica es lo
   * que hace que "La Costa" se comporte igual que "Ciudad de la Costa" en
   * todo el sistema: {@link #covers(String, String)} le da la jerarquía del
   * paraguas y {@code AgentService.toDisplayArea} la muestra con el nombre
   * canónico.
   */
  public static Optional<CoverageZone> fromLabel(String value) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      return Optional.empty();
    }
    return Arrays.stream(values())
        .filter(zone -> zone.matches(normalized))
        .findFirst();
  }

  private boolean matches(String normalized) {
    if (normalize(label).equals(normalized)) {
      return true;
    }
    return aliases.stream().anyMatch(alias -> normalize(alias).equals(normalized));
  }

  /**
   * De las zonas que un proveedor declaró (texto libre), las que Fixy NO
   * reconoce — o sea, las que no le van a traer ni un pedido.
   *
   * <p>Existe porque hasta hoy declarar una zona inexistente no fallaba: se
   * guardaba, se mostraba en el perfil y simplemente no matcheaba nunca.
   * Un proveedor puede quedar meses creyendo que cubre su barrio mientras el
   * matching lo saltea. Devolver la lista deja que la superficie que la pide
   * se lo diga de frente (ver {@code ProviderSelfResponse.unrecognizedZones}).
   *
   * <p>Preserva el texto tal cual lo escribió el proveedor: el aviso tiene
   * que mostrarle SU palabra, no una versión normalizada que no reconocería.
   */
  public static List<String> unrecognized(String... declaredZones) {
    LinkedHashSet<String> unknown = new LinkedHashSet<>();
    for (String declared : declaredZones) {
      if (declared == null) {
        continue;
      }
      for (String piece : declared.split(",")) {
        String trimmed = piece.trim();
        if (!trimmed.isBlank() && !isCovered(trimmed)) {
          unknown.add(trimmed);
        }
      }
    }
    return List.copyOf(unknown);
  }

  /** true si Fixy declara cobertura en esta zona (cualquier alias). */
  public static boolean isCovered(String value) {
    return NORMALIZED_TOKENS.contains(normalize(value));
  }

  /**
   * ¿La cobertura declarada por un proveedor ({@code providerZone}) alcanza al
   * barrio de un pedido ({@code leadZone})?
   *
   * <p>Además de la igualdad exacta, la jerarquía vale en las DOS direcciones,
   * y cada una arregla una pérdida distinta medida en el embudo de prod:
   *
   * <ol>
   *   <li><b>El proveedor declara el paraguas, el pedido nombra un barrio.</b>
   *   Carnot Clima tiene {@code primaryZone = "Ciudad de la Costa"} y Daya
   *   Dream Deco {@code city = "Ciudad de la Costa"}: ambos dicen cubrir la
   *   ciudad entera y sin embargo hoy son invisibles para un pedido en Lagomar
   *   o El Pinar, porque {@code "lagomar" != "ciudad de la costa"}.</li>
   *
   *   <li><b>El pedido dice el paraguas, el proveedor declara barrios.</b> Es
   *   el caso más frecuente del embudo: "Ciudad de la Costa" es el valor de
   *   zona más común entre los pedidos reales (35 de 135, el 26%) porque el
   *   cliente contesta con la ciudad y no con el barrio. Un proveedor que se
   *   autoregistró listando solo su barrio no ve NINGUNO de esos pedidos.</li>
   * </ol>
   *
   * <p>La dirección 2 es deliberadamente una apuesta: ofrecerle a un plomero
   * de Solymar un pedido que solo dice "Ciudad de la Costa" puede caerle lejos.
   * Se elige ofrecer igual porque el proveedor puede rechazarlo —y el rechazo
   * ya queda registrado en {@code provider_lead_declines}, así que no se le
   * vuelve a ofrecer— mientras que la alternativa de hoy es que el pedido se
   * muera sin que nadie lo vea.
   *
   * <p>Zonas que Fixy no cubre (Pocitos, Cordón) no entran en la jerarquía:
   * caen a igualdad exacta, que es lo que ya hacían.
   */
  public static boolean covers(String providerZone, String leadZone) {
    String provider = normalize(providerZone);
    String lead = normalize(leadZone);
    if (provider.isBlank() || lead.isBlank()) {
      return false;
    }
    if (provider.equals(lead)) {
      return true;
    }
    Optional<CoverageZone> providerCanonical = fromLabel(provider);
    Optional<CoverageZone> leadCanonical = fromLabel(lead);
    if (providerCanonical.isEmpty() || leadCanonical.isEmpty()) {
      return false;
    }
    return contains(providerCanonical.get(), leadCanonical.get())
        || contains(leadCanonical.get(), providerCanonical.get());
  }

  /** true si {@code umbrella} es el paraguas de {@code zone} (o la misma zona). */
  private static boolean contains(CoverageZone umbrella, CoverageZone zone) {
    return umbrella == zone || zone.parent().filter(p -> p == umbrella).isPresent();
  }
}
