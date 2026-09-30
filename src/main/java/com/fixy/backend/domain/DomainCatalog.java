package com.fixy.backend.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Core Fase 2 (ver CORE_FASE2_CONTRATO.md): catálogo de dominio de Fixy — qué
 * zonas cubre, cómo se contienen entre sí, qué categorías existen y cómo se
 * detectan — leído de {@code domain/home-services.yml} en vez de vivir en
 * código. Fuente única: antes eran dos enums ({@code CoverageZone},
 * {@code ServiceCategory}) más listas sueltas en {@code AgentService},
 * {@code LeadAgentService}, {@code WhatsAppMenuService} y los prompts, que ya
 * se habían desincronizado entre sí más de una vez (los incidentes están
 * contados en los comentarios de los métodos de abajo, que se movieron
 * intactos desde los enums).
 *
 * <p>La LÓGICA es la de los enums, cortada y pegada: lo único que cambió es de
 * dónde salen los datos. Los enums siguen existiendo como fachada (los tests
 * usan sus constantes) y delegan acá.
 *
 * <p>Fuente de los datos: si {@code FIXY_DOMAIN_CATALOG_PATH} (variable de
 * entorno o system property) apunta a un archivo legible y válido, se usa ese —
 * así se agrega una zona o categoría en prod sin recompilar—; si no existe, está
 * roto o le falta algún id del catálogo del jar, se loguea WARN y se arranca con
 * el del classpath: nunca se rompe prod por un archivo de override.
 */
public final class DomainCatalog {

  private static final Logger log = LoggerFactory.getLogger(DomainCatalog.class);

  /** Ruta del YAML del classpath (el que viaja en el jar). */
  public static final String CLASSPATH_RESOURCE = "domain/home-services.yml";
  /** Variable de entorno / system property con la ruta del override externo. */
  public static final String OVERRIDE_PROPERTY = "FIXY_DOMAIN_CATALOG_PATH";

  /** Prioridad de detección de una zona sin bloque {@code detect.priority} (después de las que sí lo traen). */
  private static final int DEFAULT_DETECT_PRIORITY = 1000;

  /** Datos de negocio para los prompts. */
  public record Business(String name, String intro, String region) {}

  private static final Object LOCK = new Object();
  private static volatile DomainCatalog instance;

  /** El catálogo de la app: carga perezosa y thread-safe, una sola vez. */
  public static DomainCatalog get() {
    DomainCatalog current = instance;
    if (current == null) {
      synchronized (LOCK) {
        current = instance;
        if (current == null) {
          current = load(overridePath());
          instance = current;
        }
      }
    }
    return current;
  }

  private static String overridePath() {
    String fromProperty = System.getProperty(OVERRIDE_PROPERTY);
    if (fromProperty != null && !fromProperty.isBlank()) {
      return fromProperty.trim();
    }
    String fromEnv = System.getenv(OVERRIDE_PROPERTY);
    return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.trim();
  }

  /**
   * Carga el catálogo: el override en {@code overridePath} si existe y es válido, si no el del
   * classpath. Público para poder testear el fallback sin tocar el estado global de {@link #get()}.
   *
   * @throws IllegalStateException si el YAML del classpath falta o es inválido (eso sí rompe el
   *     arranque: sin catálogo base Fixy no puede funcionar).
   */
  public static DomainCatalog load(String overridePath) {
    DomainCatalog builtin = parseClasspath();
    if (overridePath == null || overridePath.isBlank()) {
      log.info("DomainCatalog cargado desde classpath:{} ({} zonas, {} categorías)",
          CLASSPATH_RESOURCE, builtin.zones.size(), builtin.categories.size());
      return builtin;
    }
    Path path = Path.of(overridePath);
    try {
      if (!Files.isReadable(path)) {
        log.warn("DomainCatalog: el override {} no existe o no se puede leer; se usa classpath:{}",
            overridePath, CLASSPATH_RESOURCE);
        return builtin;
      }
      DomainCatalog override;
      try (InputStream in = Files.newInputStream(path)) {
        override = parse(in, overridePath);
      }
      List<String> missing = override.idsMissingFrom(builtin);
      if (!missing.isEmpty()) {
        log.warn("DomainCatalog: al override {} le faltan ids del catálogo base {}; se usa classpath:{}",
            overridePath, missing, CLASSPATH_RESOURCE);
        return builtin;
      }
      log.info("DomainCatalog cargado desde override {} ({} zonas, {} categorías)",
          overridePath, override.zones.size(), override.categories.size());
      return override;
    } catch (RuntimeException | IOException ex) {
      String reason = String.valueOf(ex.getMessage()).lines().findFirst().orElse("sin detalle");
      log.warn("DomainCatalog: el override {} está roto ({}); se usa classpath:{}",
          overridePath, reason, CLASSPATH_RESOURCE);
      return builtin;
    }
  }

  private static DomainCatalog parseClasspath() {
    ClassPathResource resource = new ClassPathResource(CLASSPATH_RESOURCE);
    if (!resource.exists()) {
      throw new IllegalStateException("Catálogo de dominio no encontrado en classpath: " + CLASSPATH_RESOURCE);
    }
    try (InputStream in = resource.getInputStream()) {
      return parse(in, "classpath:" + CLASSPATH_RESOURCE);
    } catch (IOException ex) {
      throw new IllegalStateException("No se pudo leer el catálogo de dominio: " + CLASSPATH_RESOURCE, ex);
    }
  }

  /** Parsea un catálogo desde un YAML; público para tests con catálogos armados a mano. */
  public static DomainCatalog parse(InputStream in, String source) {
    Object root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("YAML inválido en " + source + ": " + ex.getMessage(), ex);
    }
    if (!(root instanceof Map<?, ?> map)) {
      throw new IllegalStateException("El catálogo " + source + " no es un mapa YAML");
    }
    return fromMap(YamlReader.asMap(map, source), source);
  }

  private static DomainCatalog fromMap(Map<String, Object> root, String source) {
    YamlReader r = new YamlReader(source);
    Map<String, Object> biz = r.map(root, "business", "business");
    Business business = new Business(
        r.string(biz, "name", "business.name"),
        r.string(biz, "intro", "business.intro"),
        r.string(biz, "region", "business.region"));

    Map<String, Object> order = r.map(root, "prompt_order", "prompt_order");
    boolean umbrellaLast = r.bool(order, "umbrellaLast", "prompt_order.umbrellaLast", true);
    String areaUnknown = r.string(order, "areaUnknown", "prompt_order.areaUnknown");

    List<ZoneDef> zones = new ArrayList<>();
    for (Map<String, Object> z : r.mapList(root, "zones", "zones")) {
      String id = r.string(z, "id", "zones[].id");
      String where = "zones[" + id + "]";
      String label = r.string(z, "label", where + ".label");
      String parent = r.stringOrNull(z, "parent");
      List<String> aliases = r.stringList(z, "aliases", where + ".aliases");
      int priority = DEFAULT_DETECT_PRIORITY;
      List<String> tokens = List.of(normalize(label));
      Object detectRaw = z.get("detect");
      if (detectRaw != null) {
        Map<String, Object> detect = r.map(z, "detect", where + ".detect");
        Object prio = detect.get("priority");
        if (prio != null) {
          priority = r.integer(detect, "priority", where + ".detect.priority");
        }
        if (detect.get("tokens") != null) {
          tokens = r.stringList(detect, "tokens", where + ".detect.tokens").stream()
              .map(DomainCatalog::normalize).toList();
        }
      }
      zones.add(new ZoneDef(id, label, parent, aliases, priority, tokens));
    }

    List<CategoryDef> categories = new ArrayList<>();
    for (Map<String, Object> c : r.mapList(root, "categories", "categories")) {
      String id = r.string(c, "id", "categories[].id");
      String where = "categories[" + id + "]";
      CategoryDef.Ambiguity ambiguity = null;
      if (c.get("ambiguity") != null) {
        Map<String, Object> a = r.map(c, "ambiguity", where + ".ambiguity");
        ambiguity = new CategoryDef.Ambiguity(
            r.string(a, "alternative", where + ".ambiguity.alternative"),
            r.stringList(a, "alternativeSignals", where + ".ambiguity.alternativeSignals"),
            r.stringList(a, "keepSignals", where + ".ambiguity.keepSignals"));
      }
      categories.add(new CategoryDef(
          id,
          r.string(c, "enumName", where + ".enumName"),
          r.string(c, "label", where + ".label"),
          r.bool(c, "mvp", where + ".mvp", false),
          r.bool(c, "catchAll", where + ".catchAll", false),
          r.stringListOrEmpty(c, "keywords", where + ".keywords"),
          r.stringListOrEmpty(c, "declaredKeywords", where + ".declaredKeywords"),
          r.integerOrNull(c, "priceMin", where + ".priceMin"),
          r.integerOrNull(c, "priceMax", where + ".priceMax"),
          r.stringOrNull(c, "intakeHint"),
          r.stringOrNull(c, "classifierDescription"),
          r.stringOrNull(c, "coverageNote"),
          r.stringOrNull(c, "promptNotes"),
          r.stringOrNull(c, "menuDescription"),
          ambiguity));
    }
    return new DomainCatalog(business, umbrellaLast, areaUnknown, zones, categories, source);
  }

  // -----------------------------------------------------------------------
  // Datos
  // -----------------------------------------------------------------------

  private final Business business;
  private final boolean umbrellaLast;
  private final String areaUnknown;
  private final List<ZoneDef> zones;
  private final List<CategoryDef> categories;
  private final String source;
  /** Todas las formas normalizadas aceptadas (etiquetas canónicas + alias sin acento). */
  private final Set<String> normalizedTokens;
  private final List<ZoneDef> detectionOrder;

  private DomainCatalog(Business business, boolean umbrellaLast, String areaUnknown,
      List<ZoneDef> zones, List<CategoryDef> categories, String source) {
    this.business = business;
    this.umbrellaLast = umbrellaLast;
    this.areaUnknown = areaUnknown;
    this.zones = List.copyOf(zones);
    this.categories = List.copyOf(categories);
    this.source = source;
    validate();
    this.normalizedTokens = buildTokens();
    this.detectionOrder = buildDetectionOrder();
  }

  private void validate() {
    Set<String> zoneIds = new LinkedHashSet<>();
    for (ZoneDef zone : zones) {
      if (!zoneIds.add(zone.id())) {
        throw new IllegalStateException(source + ": zona duplicada " + zone.id());
      }
    }
    for (ZoneDef zone : zones) {
      if (zone.parentId() == null) {
        continue;
      }
      ZoneDef parent = zones.stream().filter(z -> z.id().equals(zone.parentId())).findFirst()
          .orElseThrow(() -> new IllegalStateException(
              source + ": la zona " + zone.id() + " tiene parent inexistente " + zone.parentId()));
      if (!parent.isUmbrella()) {
        throw new IllegalStateException(source + ": el parent " + parent.id() + " de " + zone.id()
            + " tiene que ser un paraguas (parent: null); la jerarquía es de un solo nivel");
      }
    }
    Set<String> ids = new LinkedHashSet<>();
    Set<String> enumNames = new LinkedHashSet<>();
    for (CategoryDef category : categories) {
      if (!ids.add(category.id())) {
        throw new IllegalStateException(source + ": categoría duplicada " + category.id());
      }
      if (!enumNames.add(category.enumName())) {
        throw new IllegalStateException(source + ": enumName duplicado " + category.enumName());
      }
    }
    for (CategoryDef category : categories) {
      if (category.ambiguity() != null && !ids.contains(category.ambiguity().alternativeId())) {
        throw new IllegalStateException(source + ": la categoría " + category.id()
            + " desempata contra una categoría inexistente " + category.ambiguity().alternativeId());
      }
    }
  }

  private List<String> idsMissingFrom(DomainCatalog base) {
    List<String> missing = new ArrayList<>();
    for (ZoneDef zone : base.zones) {
      if (zoneById(zone.id()).isEmpty()) {
        missing.add("zona " + zone.id());
      }
    }
    for (CategoryDef category : base.categories) {
      if (categoryById(category.id()).isEmpty()) {
        missing.add("categoría " + category.id());
      }
    }
    return missing;
  }

  /** De dónde se cargó ("classpath:..." o la ruta del override), para logs y tests. */
  public String source() {
    return source;
  }

  public Business business() {
    return business;
  }

  // -----------------------------------------------------------------------
  // Zonas
  // -----------------------------------------------------------------------

  /** Todas las zonas, en el orden del catálogo (el paraguas primero). */
  public List<ZoneDef> zones() {
    return zones;
  }

  public Optional<ZoneDef> zoneById(String id) {
    if (id == null) {
      return Optional.empty();
    }
    return zones.stream().filter(z -> z.id().equals(id)).findFirst();
  }

  /**
   * Zonas en el orden con que se listan en prompts y schemas: las específicas
   * en su orden y después el paraguas (regla {@code prompt_order.umbrellaLast}).
   */
  public List<ZoneDef> promptZones() {
    if (!umbrellaLast) {
      return zones;
    }
    List<ZoneDef> ordered = new ArrayList<>();
    zones.stream().filter(z -> !z.isUmbrella()).forEach(ordered::add);
    zones.stream().filter(ZoneDef::isUmbrella).forEach(ordered::add);
    return List.copyOf(ordered);
  }

  /** Valor del clasificador para "zona no definida" ("sin definir"). */
  public String areaUnknown() {
    return areaUnknown;
  }

  /**
   * Todas las etiquetas canónicas, en el orden del catálogo (el paraguas primero).
   * Lo consumen los catálogos que se le ofrecen al cliente y al proveedor.
   */
  public List<String> zoneLabels() {
    return zones.stream().map(ZoneDef::label).toList();
  }

  /**
   * Todas las formas normalizadas aceptadas (canónicas + alias sin acento).
   * Es el reemplazo de las tres copias de {@code MVP_LOCATIONS}: preguntar
   * "¿esta zona está dentro de la cobertura de Fixy?" es {@code
   * normalizedTokens().contains(normalize(zona))}.
   */
  public Set<String> normalizedTokens() {
    return normalizedTokens;
  }

  private Set<String> buildTokens() {
    Set<String> tokens = new LinkedHashSet<>();
    for (ZoneDef zone : zones) {
      tokens.add(normalize(zone.label()));
      for (String alias : zone.aliases()) {
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

  /** Igual que {@link #normalize(String)}; existe como método de instancia por la API del catálogo. */
  public String normalizeZone(String value) {
    return normalize(value);
  }

  /**
   * La zona canónica correspondiente a un texto libre, si Fixy la cubre —
   * por su etiqueta o por cualquiera de sus alias. Devolver la canónica es lo
   * que hace que "La Costa" se comporte igual que "Ciudad de la Costa" en
   * todo el sistema: {@link #covers(String, String)} le da la jerarquía del
   * paraguas y {@code AgentService.toDisplayArea} la muestra con el nombre
   * canónico.
   */
  public Optional<ZoneDef> zoneByLabel(String value) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      return Optional.empty();
    }
    return zones.stream()
        .filter(zone -> zone.matches(normalized))
        .findFirst();
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
  public List<String> unrecognized(String... declaredZones) {
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
  public boolean isCovered(String value) {
    return normalizedTokens.contains(normalize(value));
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
  public boolean covers(String providerZone, String leadZone) {
    String provider = normalize(providerZone);
    String lead = normalize(leadZone);
    if (provider.isBlank() || lead.isBlank()) {
      return false;
    }
    if (provider.equals(lead)) {
      return true;
    }
    Optional<ZoneDef> providerCanonical = zoneByLabel(provider);
    Optional<ZoneDef> leadCanonical = zoneByLabel(lead);
    if (providerCanonical.isEmpty() || leadCanonical.isEmpty()) {
      return false;
    }
    return contains(providerCanonical.get(), leadCanonical.get())
        || contains(leadCanonical.get(), providerCanonical.get());
  }

  /** true si {@code umbrella} es el paraguas de {@code zone} (o la misma zona). */
  private static boolean contains(ZoneDef umbrella, ZoneDef zone) {
    return umbrella.id().equals(zone.id()) || zone.parent().filter(p -> p.equals(umbrella.id())).isPresent();
  }

  /**
   * Zonas en el orden con que {@link #detectZone} las evalúa: las específicas
   * por {@code detect.priority} (menor primero; a igual prioridad, el orden del
   * catálogo) y al final los paraguas. Ver el comentario de {@code detect} en
   * el YAML (caso real lead #132: "montes" cayó a "Ciudad de la Costa").
   */
  private List<ZoneDef> buildDetectionOrder() {
    List<ZoneDef> ordered = new ArrayList<>();
    zones.stream().filter(z -> !z.isUmbrella())
        .sorted(Comparator.comparingInt(ZoneDef::detectPriority))
        .forEach(ordered::add);
    zones.stream().filter(ZoneDef::isUmbrella).forEach(ordered::add);
    return List.copyOf(ordered);
  }

  /**
   * La zona que menciona un texto libre (mensaje del cliente), o vacío si no
   * nombra ninguna. Recorre las zonas en el orden de detección y devuelve la
   * primera cuyo token aparece en el texto (sin acentos, minúsculas).
   */
  public Optional<ZoneDef> detectZone(String text) {
    String normalized = java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
    for (ZoneDef zone : detectionOrder) {
      for (String token : zone.detectTokens()) {
        if (normalized.contains(token)) {
          return Optional.of(zone);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * El paraguas al que un valor genérico (ej. "canelones", sin barrio) remite
   * exactamente, o vacío. Sirve a la normalización del "area" que devuelve el LLM.
   */
  public Optional<ZoneDef> umbrellaByDetectToken(String normalized) {
    return zones.stream()
        .filter(ZoneDef::isUmbrella)
        .filter(z -> z.detectTokens().contains(normalized))
        .findFirst();
  }

  // -----------------------------------------------------------------------
  // Categorías
  // -----------------------------------------------------------------------

  /** Todas las categorías (incluida "otro"), en el orden del catálogo. */
  public List<CategoryDef> categories() {
    return categories;
  }

  /** Las categorías MVP (matching real), en el orden del catálogo. */
  public List<CategoryDef> mvpCategories() {
    return categories.stream().filter(CategoryDef::mvp).toList();
  }

  /** IDs de las categorías MVP (matching real) — reemplaza los MVP_CATEGORIES duplicados. */
  public List<String> mvpIds() {
    return mvpCategories().stream().map(CategoryDef::id).toList();
  }

  /**
   * Nombres en español de las categorías MVP, para decirle al vecino qué
   * consigue Fixy HOY. Deriva del catálogo a propósito (mismo criterio que
   * {@link #zoneLabels()}): sumar una categoría no puede dejar el
   * mensaje mintiéndole a la gente, que es el bug que motivó el catálogo único.
   */
  public List<String> mvpLabels() {
    return mvpCategories().stream().map(CategoryDef::label).toList();
  }

  /** Todos los IDs conocidos, incluyendo "otro" — para el enum del JSON schema del clasificador. */
  public List<String> allCategoryIds() {
    return categories.stream().map(CategoryDef::id).toList();
  }

  public Optional<CategoryDef> categoryById(String rawId) {
    if (rawId == null || rawId.isBlank()) {
      return Optional.empty();
    }
    String normalized = rawId.toLowerCase(Locale.ROOT).trim();
    return categories.stream().filter(c -> c.id().equals(normalized)).findFirst();
  }

  public Optional<CategoryDef> categoryByEnumName(String enumName) {
    if (enumName == null) {
      return Optional.empty();
    }
    return categories.stream().filter(c -> c.enumName().equals(enumName)).findFirst();
  }

  /**
   * Detecta la categoría buscando keywords en un texto libre (mensaje del
   * cliente, problema del lead). Devuelve empty si ninguna keyword matchea —
   * los llamadores deciden si eso es "otro" o "sin detectar" según su flujo.
   * OTRO nunca matchea (no tiene keywords) — es siempre el default explícito.
   *
   * <p>Itera en orden de declaración: ese orden ES comportamiento (ver el
   * comentario de decoracion_fiestas en el YAML).
   */
  public Optional<CategoryDef> detectCategory(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }
    // "aire libre" no es el aire acondicionado: se quita ANTES de matchear
    // para que "decoración al aire libre" no caiga en aires (el keyword
    // suelto "aire" existe porque así habla la gente: "aire roto").
    String normalized = text.toLowerCase(Locale.ROOT).replace("aire libre", " ");
    for (CategoryDef category : categories) {
      for (String keyword : category.keywords()) {
        if (normalized.contains(keyword)) {
          return Optional.of(refineAmbiguity(normalized, category));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Desempate determinista pastelería vs decoración ("lo crítico va en
   * código, no en prompt"): "cumpleaños"/"fiesta" son keywords de pastelería
   * por historia, así que "decoración para el cumpleaños" caía en pastelería
   * — lo midió el banco (mixto_cumple_decoracion) y lo fallaban tanto el
   * heurístico como gpt-5-mini (2026-07-27). Regla, espejo de la del prompt:
   * si el texto trae señales de ambientación y NINGUNA señal inequívoca de
   * comida (torta, mesa dulce...), es decoración. "Torta para el cumple" no
   * se toca: torta es señal fuerte de comida. Usable por cualquier camino de
   * clasificación (heurístico y LLM) vía {@link #refineCategoryId}.
   *
   * <p>Las señales viven en el YAML, bajo la categoría que las usa
   * ({@code ambiguity}).
   */
  private CategoryDef refineAmbiguity(String normalizedText, CategoryDef detected) {
    CategoryDef.Ambiguity ambiguity = detected.ambiguity();
    if (ambiguity == null) {
      return detected;
    }
    boolean mentionsAlternative = ambiguity.alternativeSignals().stream().anyMatch(normalizedText::contains);
    boolean strongKeep = ambiguity.keepSignals().stream().anyMatch(normalizedText::contains);
    return mentionsAlternative && !strongKeep
        ? categoryById(ambiguity.alternativeId()).orElse(detected)
        : detected;
  }

  /**
   * Versión por id para los caminos LLM: aplica el mismo desempate sobre la
   * categoría que extrajo el modelo. Devuelve el id refinado (o el original
   * si no hay nada que corregir / el id no se reconoce).
   */
  public String refineCategoryId(String text, String categoryId) {
    if (text == null || categoryId == null) {
      return categoryId;
    }
    return categoryById(categoryId)
        .map(c -> refineAmbiguity(text.toLowerCase(Locale.ROOT), c).id())
        .orElse(categoryId);
  }

  /** Nombre humano en español para mostrarle al cliente; "tu pedido" si no se reconoce el id. */
  public String humanLabel(String rawId) {
    return categoryById(rawId).map(CategoryDef::label)
        .orElse(rawId == null || rawId.isBlank() ? "tu pedido" : rawId);
  }

  /** Guion de intake para el agente (o null). Ver {@link CategoryDef#intakeHint()}. */
  public String intakeHintForId(String rawId) {
    return categoryById(rawId).map(CategoryDef::intakeHint).orElse(null);
  }

  /** Rango orientativo en UYU para el id dado, o null si no hay categoría o no hay rango cargado. */
  public String priceRangeLabel(String rawId) {
    return categoryById(rawId).map(CategoryDef::priceRangeLabel).orElse(null);
  }
}
