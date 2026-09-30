package com.fixy.backend.domain;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Core Fase 2 (ver CORE_FASE2_CONTRATO.md): el contrato del turno conversacional — qué campos
 * extrae el LLM, qué acciones puede pedir, qué hace falta para matchear y qué tools expone Fixy —
 * leído de {@code domain/home-services-playbook.yml}. Genera el json_schema del turno
 * ({@link #turnSchema()}) y el bloque "FORMATO DE SALIDA" del prompt ({@link #outputFormatBlock()})
 * a partir de una sola declaración; los enums de zona y categoría salen del {@link DomainCatalog}.
 */
public final class Playbook {

  private static final Logger log = LoggerFactory.getLogger(Playbook.class);

  /** Ruta del YAML del classpath. */
  public static final String CLASSPATH_RESOURCE = "domain/home-services-playbook.yml";

  private static volatile Playbook instance;

  /** El playbook de la app: carga perezosa y thread-safe, una sola vez, contra {@link DomainCatalog#get()}. */
  public static Playbook get() {
    Playbook current = instance;
    if (current == null) {
      synchronized (Playbook.class) {
        current = instance;
        if (current == null) {
          ClassPathResource resource = new ClassPathResource(CLASSPATH_RESOURCE);
          if (!resource.exists()) {
            throw new IllegalStateException("Playbook no encontrado en classpath: " + CLASSPATH_RESOURCE);
          }
          try (InputStream in = resource.getInputStream()) {
            current = parse(in, "classpath:" + CLASSPATH_RESOURCE, DomainCatalog.get());
          } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer el playbook: " + CLASSPATH_RESOURCE, ex);
          }
          log.info("Playbook cargado desde classpath:{} ({} campos, tools {})",
              CLASSPATH_RESOURCE, current.fields.size(), current.tools);
          instance = current;
        }
      }
    }
    return current;
  }

  /**
   * Un campo del turno. {@code enumSource} es {@code "categories"}, {@code "zones"} o una lista literal.
   */
  public record Field(
      String name,
      String type,
      Object enumSource,
      String enumScope,
      List<String> enumOrder,
      List<String> enumExtra,
      boolean nullable,
      String description
  ) {
    public boolean hasEnum() {
      return enumSource != null;
    }
  }

  private final DomainCatalog catalog;
  private final String source;
  private final String outputFormatHeader;
  private final String replyDescription;
  private final List<Field> fields;
  private final List<String> actionTypes;
  private final String actionReasonDescription;
  private final String actionSummaryDescription;
  private final List<String> requiredForMatching;
  private final String contactAskField;
  private final List<String> tools;

  private Playbook(DomainCatalog catalog, String source, Map<String, Object> root) {
    this.catalog = catalog;
    this.source = source;
    YamlReader r = new YamlReader(source);
    this.outputFormatHeader = r.string(root, "outputFormatHeader", "outputFormatHeader");
    this.replyDescription = r.string(r.map(root, "reply", "reply"), "description", "reply.description");
    this.fields = new ArrayList<>();
    for (Map<String, Object> f : r.mapList(root, "fields", "fields")) {
      String name = r.string(f, "name", "fields[].name");
      String where = "fields[" + name + "]";
      Object enumSource = f.get("enumSource");
      if (enumSource != null && !(enumSource instanceof String) && !(enumSource instanceof List<?>)) {
        throw new IllegalStateException(source + ": " + where + ".enumSource tiene que ser categories, zones o una lista");
      }
      if (enumSource instanceof String s && !s.equals("categories") && !s.equals("zones")) {
        throw new IllegalStateException(source + ": " + where + ".enumSource desconocido: " + s);
      }
      List<String> literal = null;
      if (enumSource instanceof List<?>) {
        literal = r.stringList(f, "enumSource", where + ".enumSource");
      }
      fields.add(new Field(
          name,
          r.string(f, "type", where + ".type"),
          literal != null ? literal : enumSource,
          r.stringOrNull(f, "enumScope"),
          r.stringListOrEmpty(f, "enumOrder", where + ".enumOrder"),
          r.stringListOrEmpty(f, "enumExtra", where + ".enumExtra"),
          r.bool(f, "nullable", where + ".nullable", false),
          r.stringOrNull(f, "description")));
    }
    Map<String, Object> actions = r.map(root, "actions", "actions");
    this.actionTypes = r.stringList(actions, "types", "actions.types");
    this.actionReasonDescription = r.string(actions, "reason", "actions.reason");
    this.actionSummaryDescription = r.string(actions, "summary", "actions.summary");
    this.requiredForMatching = r.stringList(root, "requiredForMatching", "requiredForMatching");
    this.contactAskField = r.string(r.map(root, "contactAsk", "contactAsk"), "field", "contactAsk.field");
    this.tools = r.stringList(root, "tools", "tools");
    validate();
  }

  /** Parsea un playbook desde un YAML contra el catálogo dado; público para tests. */
  public static Playbook parse(InputStream in, String source, DomainCatalog catalog) {
    Object root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("YAML inválido en " + source + ": " + ex.getMessage(), ex);
    }
    return new Playbook(catalog, source, YamlReader.asMap(root, source));
  }

  private void validate() {
    List<String> names = fields.stream().map(Field::name).toList();
    for (String required : requiredForMatching) {
      if (!names.contains(required)) {
        throw new IllegalStateException(source + ": requiredForMatching menciona un campo inexistente: " + required);
      }
    }
    if (!names.contains(contactAskField)) {
      throw new IllegalStateException(source + ": contactAsk.field inexistente: " + contactAskField);
    }
    for (Field field : fields) {
      if ("categories".equals(field.enumSource()) && field.enumScope() != null && !"mvp".equals(field.enumScope())) {
        throw new IllegalStateException(source + ": fields[" + field.name() + "].enumScope desconocido: " + field.enumScope());
      }
      for (String zoneId : field.enumOrder()) {
        if (catalog.zoneById(zoneId).isEmpty()) {
          throw new IllegalStateException(source + ": fields[" + field.name() + "].enumOrder menciona una zona inexistente: " + zoneId);
        }
      }
    }
  }

  public List<Field> fields() {
    return List.copyOf(fields);
  }

  public Optional<Field> field(String name) {
    return fields.stream().filter(f -> f.name().equals(name)).findFirst();
  }

  /** Tipos de acción que el LLM puede pedir: {@code none}, {@code escalate}. */
  public List<String> actionTypes() {
    return actionTypes;
  }

  /** Campos que tienen que estar definidos para que un pedido entre a matching. */
  public List<String> requiredForMatching() {
    return requiredForMatching;
  }

  /** El campo del teléfono de contacto (el pedido en sí vive en la política del turno). */
  public String contactAskField() {
    return contactAskField;
  }

  /** Tools que Fixy expone a asistentes externos. */
  public List<String> tools() {
    return tools;
  }

  /** Los valores permitidos de un campo con enum, ya resueltos contra el catálogo (o null si no tiene). */
  public List<String> enumValues(Field field) {
    if (!field.hasEnum()) {
      return null;
    }
    List<String> values = new ArrayList<>();
    if (field.enumSource() instanceof List<?> literal) {
      literal.forEach(v -> values.add(String.valueOf(v)));
    } else if ("categories".equals(field.enumSource())) {
      List<CategoryDef> categories = "mvp".equals(field.enumScope()) ? catalog.mvpCategories() : catalog.categories();
      categories.forEach(c -> values.add(c.id()));
    } else {
      values.addAll(zoneLabelsInOrder(field.enumOrder()));
    }
    values.addAll(field.enumExtra());
    return List.copyOf(values);
  }

  /**
   * Etiquetas de zona en el orden de prompts del catálogo (específicas y paraguas al final),
   * con las de {@code enumOrder} primero y en ese orden: el orden histórico del schema del
   * turno, que difiere del del clasificador de intake. Las zonas no listadas van después de las
   * listadas y antes del paraguas.
   */
  private List<String> zoneLabelsInOrder(List<String> enumOrder) {
    List<ZoneDef> ordered = new ArrayList<>();
    for (String id : enumOrder) {
      catalog.zoneById(id).ifPresent(ordered::add);
    }
    for (ZoneDef zone : catalog.promptZones()) {
      if (!ordered.contains(zone) && !zone.isUmbrella()) {
        ordered.add(zone);
      }
    }
    for (ZoneDef zone : catalog.promptZones()) {
      if (!ordered.contains(zone)) {
        ordered.add(zone);
      }
    }
    return ordered.stream().map(ZoneDef::label).toList();
  }

  /**
   * json_schema del turno conversacional, exigido por Cloudflare Workers AI vía response_format
   * (ollama/openai lo ignoran y siguen la instrucción de formato del prompt, como siempre).
   * Movido desde {@code LeadAgentService.turnJsonSchema}: el schema es del TURNO, no del transporte.
   */
  public Map<String, Object> turnSchema() {
    Map<String, Object> extracted = new LinkedHashMap<>();
    for (Field field : fields) {
      Map<String, Object> property = new LinkedHashMap<>();
      property.put("type", field.type());
      List<String> values = enumValues(field);
      if (values != null) {
        property.put("enum", values);
      }
      extracted.put(field.name(), property);
    }
    Map<String, Object> action = new LinkedHashMap<>();
    action.put("type", Map.of("type", "string", "enum", actionTypes));
    action.put("reason", Map.of("type", "string"));
    action.put("summary", Map.of("type", "string"));

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("reply", Map.of("type", "string"));
    properties.put("extracted", Map.of("type", "object", "properties", extracted));
    properties.put("action", Map.of("type", "object", "properties", action));
    return Map.of(
        "type", "object",
        "properties", properties,
        "required", List.of("reply", "extracted"));
  }

  /**
   * El bloque "FORMATO DE SALIDA" del prompt del turno: la estructura JSON que se le pide al LLM,
   * con los enums resueltos y la descripción de cada campo. Sin salto de línea final.
   */
  public String outputFormatBlock() {
    StringBuilder out = new StringBuilder();
    out.append(outputFormatHeader).append('\n');
    out.append("{\n");
    out.append("  \"reply\": ").append(quote(replyDescription)).append(",\n");
    out.append("  \"extracted\": {\n");
    for (int i = 0; i < fields.size(); i++) {
      Field field = fields.get(i);
      String value;
      List<String> values = enumValues(field);
      if (values != null) {
        value = String.join("|", Stream.concat(values.stream(),
            field.nullable() ? Stream.of("null") : Stream.<String>empty()).toList());
      } else {
        value = field.description() == null ? "" : field.description();
      }
      out.append("    ").append(quote(field.name())).append(": ").append(quote(value))
          .append(i < fields.size() - 1 ? "," : "").append('\n');
    }
    out.append("  },\n");
    out.append("  \"action\": {\n");
    out.append("    \"type\": ").append(quote(String.join("|", actionTypes))).append(",\n");
    out.append("    \"reason\": ").append(quote(actionReasonDescription)).append(",\n");
    out.append("    \"summary\": ").append(quote(actionSummaryDescription)).append('\n');
    out.append("  }\n");
    out.append("}");
    return out.toString();
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }
}
