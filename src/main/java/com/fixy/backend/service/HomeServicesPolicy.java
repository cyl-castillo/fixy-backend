package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Core Fase 1 (paso 2/3, ver CORE_FASE1_CONTRATO.md): guardas y heurísticas
 * deterministas de Fixy ("servicios del hogar") que antes vivían como
 * estáticos/privados en {@link LeadAgentService} — ack en espera, pregunta de
 * precio, zona/categoría/teléfono mencionados por el cliente, corrección
 * explícita, pregunta de confianza, LLM atascado repitiéndose, tráfico
 * sintético (humo). Refactor puro: cada método es copia textual del
 * original, comentarios de casos reales incluidos.
 *
 * <p>Los que son funciones puras (no necesitan historial de mensajes ni otro
 * colaborador) quedan {@code public static}: no ganan nada llamándose a
 * través de la interfaz {@link TurnPolicy}, y así los siguen pudiendo testear
 * directo (varios ya se testeaban así antes de este refactor, contra
 * {@code LeadAgentService}, que ahora delega acá). Los que necesitan mirar
 * los mensajes del lead ({@link LeadMessageService}) o el clasificador de
 * zona de {@link AgentService} son la única implementación de
 * {@link TurnPolicy}, inyectada en {@link LeadAgentService}.
 */
@Service
public class HomeServicesPolicy implements TurnPolicy {

  /** Mismo valor que {@code LeadAgentService.HISTORY_LIMIT}: cuántos
   * mensajes recientes del lead se miran para las guardas de historial. */
  private static final int HISTORY_LIMIT = 10;

  private final LeadMessageService leadMessageService;
  private final AgentService agentService;

  public HomeServicesPolicy(LeadMessageService leadMessageService, AgentService agentService) {
    this.leadMessageService = leadMessageService;
    this.agentService = agentService;
  }

  // ---------------------------------------------------------------------
  // TurnPolicy (necesitan historial de mensajes u otro colaborador)
  // ---------------------------------------------------------------------

  /** Una zona extraída por el LLM solo se acepta si aparece textualmente
   * (case-insensitive, sin acentos) en algún mensaje del CLIENTE de la
   * conversación reciente. */
  @Override
  public boolean isZoneMentionedByCustomer(Long leadId, String zone) {
    if (zone == null || zone.isBlank()) {
      return false;
    }
    String needle = stripAccents(zone.toLowerCase(Locale.ROOT)).trim();
    if (needle.isEmpty()) {
      return false;
    }
    // Matching por TOKENS distintivos, no por frase completa: el cliente
    // escribe "lomas" y el LLM canonicaliza a "Lomas de Solymar" (correcto) —
    // la versión anterior exigía la frase entera y rechazaba la zona real
    // (lead #123). Un token distintivo (>=4 letras, sin conectores) del
    // nombre canónico alcanza; "hola" sigue sin validar "Ciudad de la Costa".
    List<String> tokens = java.util.Arrays.stream(needle.split("\\s+"))
        .filter(t -> t.length() >= 4 && !ZONE_STOPWORDS.contains(t))
        .toList();
    if (tokens.isEmpty()) {
      return false;
    }
    return leadMessageService.recentForAgent(leadId, HISTORY_LIMIT).stream()
        .filter(m -> "customer".equals(m.getSender()) && m.getText() != null)
        .map(m -> stripAccents(m.getText().toLowerCase(Locale.ROOT)))
        .anyMatch(text -> tokens.stream().anyMatch(text::contains));
  }

  private static final java.util.Set<String> ZONE_STOPWORDS =
      java.util.Set.of("de", "del", "la", "las", "el", "los", "san", "santa");

  /** Una categoría extraída por el LLM solo se acepta si el CLIENTE dio algún
   * rastro de ella en sus propios mensajes: o bien
   * {@link com.fixy.backend.model.ServiceCategory#detectFromText} sobre el
   * texto del cliente devuelve esa misma categoría, o alguna de sus keywords
   * (ver {@link com.fixy.backend.model.ServiceCategory#keywords()}) aparece
   * ahí (sin acentos, case-insensitive). Mismo patrón que
   * isZoneMentionedByCustomer — evita que el LLM le presuma una categoría a
   * un cliente que solo dijo "hola" (leads #116/#119 en prod). */
  @Override
  public boolean isCategoryMentionedByCustomer(Long leadId, String category) {
    if (category == null || category.isBlank()) {
      return false;
    }
    java.util.Optional<com.fixy.backend.model.ServiceCategory> target =
        com.fixy.backend.model.ServiceCategory.fromId(category);
    if (target.isEmpty()) {
      // Categoría desconocida para el catálogo (no debería pasar dado el enum
      // del schema, pero si pasa no hay nada que validar contra keywords):
      // se rechaza, es más seguro que aceptar algo que no podemos verificar.
      return false;
    }
    String customerText = stripAccents(leadMessageService.recentForAgent(leadId, HISTORY_LIMIT).stream()
        .filter(m -> "customer".equals(m.getSender()) && m.getText() != null)
        .map(m -> m.getText().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(" ")));
    if (customerText.isBlank()) {
      return false;
    }
    // 1) Clasificador heurístico laxo sobre el texto del cliente: si coincide
    // con la misma categoría, es la validación más fuerte.
    java.util.Optional<com.fixy.backend.model.ServiceCategory> detected =
        com.fixy.backend.model.ServiceCategory.detectFromText(customerText);
    if (detected.isPresent() && detected.get() == target.get()) {
      return true;
    }
    // 2) Si detectFromText matcheó OTRA categoría primero (la búsqueda es
    // "primer match" en orden del enum), igual aceptamos si alguna keyword
    // propia de la categoría extraída aparece en el texto del cliente.
    for (String keyword : target.get().keywords()) {
      if (customerText.contains(stripAccents(keyword))) {
        return true;
      }
    }
    return false;
  }

  /** true si la respuesta generada es (normalizada) igual al último mensaje
   * que el agente ya mandó — señal de LLM en loop. */
  @Override
  public boolean isStuckRepeatingItself(Long leadId, String reply) {
    if (reply == null || reply.isBlank()) {
      return false;
    }
    List<LeadMessage> recent = leadMessageService.recentForAgent(leadId, HISTORY_LIMIT);
    // Contra los últimos 3 mensajes del agente, no solo el último: el 8B
    // también re-hace preguntas VIEJAS ya respondidas (lead #131: volvió a
    // "¿qué tamaño tiene el jardín?" dos preguntas después del "30 m").
    int checked = 0;
    for (int i = recent.size() - 1; i >= 0 && checked < 3; i--) {
      LeadMessage m = recent.get(i);
      if ("fixy".equals(m.getSender())) {
        checked++;
        if (tokenSimilarity(normalizeForComparison(m.getText()),
            normalizeForComparison(reply)) >= 0.8) {
          return true;
        }
      }
    }
    return false;
  }

  /** true si algún mensaje del CLIENTE de este lead trae la marca [smoke] (tráfico sintético). */
  @Override
  public boolean customerMentionedSmoke(Long leadId) {
    try {
      return leadMessageService.recentForAgent(leadId, 10).stream()
          .anyMatch(m -> "customer".equals(m.getSender())
              && com.fixy.backend.model.SmokeTraffic.marks(m.getText()));
    } catch (Exception ex) {
      return false;
    }
  }

  /**
   * Señales explícitas de los mensajes del cliente (keywords de categoría y
   * zona) pisan lo extraído por el LLM — la base determinista de las
   * correcciones "me equivoqué". Usado por el camino LLM; opera sobre la
   * tanda de mensajes pendientes del turno con la misma semántica secuencial
   * que detectCategoryFromMessages.
   */
  @Override
  public Map<String, String> withMessageSignals(List<String> messages, Map<String, String> extracted) {
    String cat = detectCategoryFromMessages(messages);
    String zone = null;
    String phone = null;
    for (String message : messages) {
      String z = agentService.areaMentionedIn(message);
      if (z != null) {
        zone = z; // la última mención gana, igual que en turnos secuenciales
      }
      if (phone == null) {
        phone = phoneMentionedIn(message);
      }
    }
    if (cat == null && zone == null && phone == null) {
      return extracted;
    }
    Map<String, String> merged = extracted == null
        ? new java.util.HashMap<>() : new java.util.HashMap<>(extracted);
    if (cat != null) {
      merged.put("category", cat);
    }
    if (zone != null) {
      merged.put("zone", zone);
    }
    if (phone != null && !merged.containsKey("phone")) {
      merged.put("phone", phone);
    }
    return merged;
  }

  // ---------------------------------------------------------------------
  // Funciones puras (sin historial ni colaboradores) — públicas y estáticas
  // ---------------------------------------------------------------------

  private static final java.util.Set<String> ACKNOWLEDGMENTS = java.util.Set.of(
      "ok", "oka", "okey", "okay", "dale", "gracias", "muchas gracias", "perfecto",
      "listo", "genial", "buenisimo", "barbaro", "ta", "va", "de acuerdo", "entendido",
      "joya", "espero", "aguardo", "bueno", "bien");

  /** true si el mensaje es un cierre/asentimiento corto ("ok", "gracias"). */
  public static boolean isAcknowledgment(String message) {
    if (message == null) {
      return false;
    }
    String normalized = stripAccents(message.toLowerCase(Locale.ROOT))
        .replaceAll("[^a-z ]", "").trim();
    return !normalized.isEmpty() && normalized.length() <= 20
        && ACKNOWLEDGMENTS.contains(normalized);
  }

  private static final List<String> PRICE_QUESTION_KEYWORDS =
      List.of("cuanto", "cuánto", "precio", "sale", "cuesta", "vale");

  /** Detecta si el mensaje del cliente es una pregunta de precio (fallback sin LLM,
   * ver PLAN_SUPERAPP_CLIENTE.md Cotización Estimada punto 3). Heurística simple por
   * keywords, igual de espíritu que el resto de los clasificadores heurísticos del repo. */
  public static boolean isPriceQuestion(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    return PRICE_QUESTION_KEYWORDS.stream().anyMatch(normalized::contains);
  }

  /**
   * Respuesta del fallback heurístico a una pregunta de precio: si hay categoría
   * definida y con rango cargado, responde el rango con el disclaimer de siempre.
   * Si hay categoría pero sin rango cargado, es honesto: el proveedor cotiza.
   * Si no hay categoría todavía, pide el dato antes de poder ayudar con precio.
   */
  public static String priceReply(Lead lead) {
    boolean hasCategory = lead.getDetectedCategory() != null && !lead.getDetectedCategory().isBlank()
        && !"otro".equalsIgnoreCase(lead.getDetectedCategory());
    if (!hasCategory) {
      return "Para darte una idea de precio primero necesito saber qué necesitás arreglar — ¿de qué se trata?";
    }
    String category = com.fixy.backend.model.ServiceCategory.humanLabel(lead.getDetectedCategory());
    String range = com.fixy.backend.model.ServiceCategory.priceRangeLabelForId(lead.getDetectedCategory());
    if (range == null) {
      return "El precio de %s lo termina de confirmar el proveedor cuando vea el trabajo, así que no te quiero tirar un número inventado."
          .formatted(category);
    }
    // CTA post-precio (simulación 2026-08-06, persona "pregunta_precio": la
    // conversación moría después del rango — precio sin próximo paso es un
    // callejón sin salida).
    boolean zoneKnown = lead.getLocation() != null && !lead.getLocation().isBlank()
        && !"sin definir".equalsIgnoreCase(lead.getLocation());
    String cta = zoneKnown
        ? " ¿Querés que te busque uno en %s?".formatted(lead.getLocation())
        : " Si querés te consigo uno: ¿en qué zona estás?";
    return "Para %s el rango orientativo ronda %s (visita + trabajo simple), pero el precio final te lo confirma el proveedor cuando vea el trabajo.%s"
        .formatted(category, range, cta);
  }

  /**
   * true si la respuesta del LLM debe reemplazarse por la pregunta
   * determinista de zona: categoría ya conocida, zona todavía faltante (y no
   * llegó en este turno), el cliente NO está preguntando algo él mismo (ahí
   * el LLM debe poder responder libre), y la respuesta generada no pide la
   * zona. En ese estado, cualquier otra repregunta es una respuesta rota
   * (caso real lead #138). Estático y puro para testearlo sin contexto.
   */
  public static boolean shouldForceZoneQuestion(
      boolean categoryKnown,
      String location,
      String lastCustomerMsg,
      String reply,
      boolean zoneArrivedThisTurn
  ) {
    if (!categoryKnown || zoneArrivedThisTurn) {
      return false;
    }
    boolean zoneMissing = location == null || location.isBlank() || "sin definir".equalsIgnoreCase(location);
    if (!zoneMissing) {
      return false;
    }
    if (lastCustomerMsg != null && (lastCustomerMsg.contains("?") || lastCustomerMsg.contains("¿"))) {
      return false;
    }
    return !asksForZone(reply);
  }

  public static boolean shouldAskContactPhone(
      boolean categoryKnown, boolean zoneKnown, String currentPhone,
      boolean phoneArrivedThisTurn, String reply) {
    if (!categoryKnown || !zoneKnown) {
      return false;
    }
    if (phoneArrivedThisTurn || (currentPhone != null && !currentPhone.isBlank())) {
      return false;
    }
    return !asksForContactPhone(reply);
  }

  /** true si la respuesta ya pide teléfono/WhatsApp (insensible a acentos). */
  public static boolean asksForContactPhone(String reply) {
    if (reply == null || reply.isBlank()) {
      return false;
    }
    String normalized = java.text.Normalizer.normalize(reply.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
    return normalized.contains("whatsapp")
        || normalized.contains("telefono")
        || (normalized.contains("numero") && normalized.contains("contact"));
  }

  /** true si la respuesta menciona la zona/ubicación como pregunta o pedido (insensible a acentos). */
  public static boolean asksForZone(String reply) {
    if (reply == null || reply.isBlank()) {
      return false;
    }
    String normalized = java.text.Normalizer.normalize(reply.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
    return normalized.contains("zona")
        || normalized.contains("barrio")
        || normalized.contains("donde")
        || normalized.contains("ubicac")
        || normalized.contains("direccion");
  }

  /** Patrón de celular uruguayo: 09X + 7 dígitos, con o sin +598/espacios/guiones. */
  private static final java.util.regex.Pattern UY_PHONE = java.util.regex.Pattern.compile(
      "(?:(?:\\+?598)[\\s.-]?0?|0)(9\\d(?:[\\s.-]?\\d){6})(?!\\d)");

  /**
   * Teléfono detectado por REGEX en el texto del cliente (simulación
   * 2026-08-06, persona "happy_path": escribió 'mi teléfono es 099888111'
   * en el primer mensaje, el lead quedó sin teléfono, y encima el agente le
   * volvió a pedir el WhatsApp — doble vergüenza). Lo crítico va en código:
   * si el cliente YA dio el número, se captura pase lo que pase con el LLM.
   */
  public static String phoneMentionedIn(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    java.util.regex.Matcher m = UY_PHONE.matcher(message);
    if (!m.find()) {
      return null;
    }
    String digits = "0" + m.group(1).replaceAll("\\D", "");
    return digits.length() == 9 ? digits : null;
  }

  /**
   * Detección de categoría sobre una tanda de mensajes pendientes,
   * reproduciendo la semántica secuencial (un turno por mensaje): el PRIMER
   * mensaje que detecta categoría gana, y uno posterior solo la pisa si trae
   * intención explícita de corrección. detectFromText sobre el texto
   * concatenado NO sirve acá: itera categorías en orden de declaración
   * (plomería antes que mandados) y el "agua" del segundo mensaje ganaría
   * sobre el "supermercado" del primero — exactamente el bug del smoke #236.
   * Estático para testear sin contexto, mismo patrón que shouldForceZoneQuestion.
   */
  public static String detectCategoryFromMessages(List<String> messages) {
    String category = null;
    for (String message : messages) {
      String detected = com.fixy.backend.model.ServiceCategory.detectFromText(message)
          .map(com.fixy.backend.model.ServiceCategory::id)
          .orElse(null);
      if (detected == null) {
        continue;
      }
      if (category == null || isExplicitCorrection(message)) {
        category = detected;
      }
    }
    return category;
  }

  /**
   * Frases explícitas de corrección de categoría ("me equivoqué", "error,
   * era...", "en realidad es...", "no, mejor..."). Prueba real de Carlos
   * 2026-08-07 (lead #235): pidió mandados y su nota de voz "quiero agua en
   * el Tata" (transcripta "agua enlatada") re-clasificó el pedido a plomería
   * — la lista de un mandado SIEMPRE va a nombrar productos que coinciden
   * con keywords de otras categorías (agua, torta, pasto...). Regla nueva:
   * con categoría ya puesta, cambiarla exige intención explícita de
   * corrección; sin ella, la mención suelta de una keyword no toca nada.
   */
  private static final java.util.regex.Pattern CORRECTION_PHRASES = java.util.regex.Pattern.compile(
      "(?i)me\\s+equivoq|\\berror\\b|en\\s+realidad|quise\\s+decir|no\\s+era\\s+eso|no\\s+es\\s+eso"
          + "|no,?\\s+mejor|cambi[aá]\\w*\\s+(la\\s+)?categor[ií]a|no\\s+es\\s+de\\s|era\\s+de\\s");

  public static boolean isExplicitCorrection(String message) {
    return message != null && CORRECTION_PHRASES.matcher(message).find();
  }

  /** Señales de pregunta de confianza/seguridad sobre quién viene a la casa. */
  private static final List<String> TRUST_QUESTION_KEYWORDS = List.of(
      "de confianza", "confiable", "quien viene", "quién viene", "quien es el que viene",
      "es seguro", "son seguros", "verificado", "verificados", "antecedentes");

  public static boolean isTrustQuestion(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    return TRUST_QUESTION_KEYWORDS.stream().anyMatch(normalized::contains);
  }

  /**
   * Frases con las que una respuesta afirma que la búsqueda de proveedor
   * sigue abierta. Solo se usan cuando el lead YA tiene proveedor encima:
   * ahí cualquiera de estas es literalmente falsa (lead #257).
   */
  private static final List<String> STILL_SEARCHING_PHRASES = List.of(
      "estoy buscando", "sigo buscando", "buscando un proveedor", "buscando uno",
      "buscando a alguien", "voy a buscar", "busco un proveedor",
      "no tengo proveedor", "no tenemos proveedor", "no tengo un proveedor",
      "no tenemos un proveedor", "no hay proveedor", "todavia no se sumo nadie");

  /** true si la respuesta afirma que todavía está buscando proveedor (insensible a acentos). */
  public static boolean claimsStillSearching(String reply) {
    if (reply == null || reply.isBlank()) {
      return false;
    }
    String normalized = stripAccents(reply.toLowerCase(Locale.ROOT));
    return STILL_SEARCHING_PHRASES.stream().anyMatch(normalized::contains);
  }

  /**
   * true si el pedido YA tiene un proveedor concreto encima: contactado y
   * esperando su confirmación (PROVIDER_CONTACTED) o ya aceptado
   * (ASSIGNED/IN_PROGRESS). En cualquiera de esos estados decir "estoy
   * buscando un proveedor para vos" es falso.
   */
  public static boolean hasProviderOnTheLine(Lead lead) {
    return lead != null
        && lead.getAssignedProviderId() != null
        && (lead.getStatus() == com.fixy.backend.model.LeadStatus.PROVIDER_CONTACTED
            || lead.getStatus() == com.fixy.backend.model.LeadStatus.ASSIGNED
            || lead.getStatus() == com.fixy.backend.model.LeadStatus.IN_PROGRESS);
  }

  /**
   * Pregunta genérica de arranque. Constante porque el guard de
   * {@code LeadAgentService.respondWithHeuristicFallback} la compara por
   * identidad: es la ÚNICA respuesta del fallback que no reconoce nada de lo
   * que el vecino dijo, y por lo tanto la única que repetida deja la
   * conversación sin salida. Fuente única — {@code LeadAgentService} la
   * re-exporta bajo el mismo nombre porque los tests existentes la referencian
   * como {@code LeadAgentService.ASK_WHAT_HAPPENED}.
   */
  public static final String ASK_WHAT_HAPPENED =
      "Contame un poco más: ¿qué te pasa o qué necesitás arreglar en tu casa?";

  /**
   * true si la respuesta es la repregunta genérica, sola o precedida por el
   * ack de la zona ya conocida. El guard de {@code respondWithHeuristicFallback}
   * la comparaba por identidad contra {@code ASK_WHAT_HAPPENED}, así que el
   * pedido sin categoría PERO con zona (lead #268) no entraba al escalamiento
   * y terminaba en silencio.
   */
  public static boolean isAskWhatHappened(String reply) {
    return reply != null && reply.endsWith(ASK_WHAT_HAPPENED);
  }

  /**
   * Qué consigue Fixy HOY, en una frase. Es lo que recibe el vecino cuyo
   * pedido no se entendió, en lugar de la promesa que Fixy no puede cumplir
   * ("en breve te contactan": 26 de los 27 pedidos abiertos no tienen
   * teléfono, y ningún humano contestó nunca uno de estos chats).
   *
   * <p>Dato que lo motiva (guardia diaria 2026-09-03): los cuatro pedidos
   * chat-first anteriores al #265 —carpintero para un sillón, limpiar un
   * parrillero, un flete para una mudanza, armar un ropero— murieron acá.
   * Ninguno es ambiguo para una persona; los cuatro son oficios que Fixy no
   * tiene. Al vecino no se le decía ni que no lo cubrimos ni qué sí, así que
   * no podía corregirse ("ah, pero también tengo el aire roto") y se iba.
   *
   * <p>No afirma "ese servicio no lo cubrimos": por acá también cae el que
   * escribió "hola" dos veces, y ahí sería mentira. Dice lo único que es
   * cierto en los dos casos —no se entendió— y muestra la carta. La lista
   * sale de {@link com.fixy.backend.model.ServiceCategory#MVP_LABELS}, fuente
   * única, para que sumar una categoría no deje este mensaje desactualizado.
   */
  public static String whatFixyCoversReply() {
    List<String> labels = com.fixy.backend.model.ServiceCategory.MVP_LABELS;
    String servicios = labels.size() < 2
        ? String.join(", ", labels)
        : String.join(", ", labels.subList(0, labels.size() - 1)) + " y " + labels.get(labels.size() - 1);
    return "Perdón, no te terminé de entender. Te cuento qué consigo hoy: " + servicios
        + ". Si lo tuyo es alguno de esos decime cuál y sigo con tu pedido; si es otra cosa, "
        + "ya lo anoté y se lo pasé al equipo — así decidimos qué servicio sumar.";
  }

  /** true si el pedido está marcado como tráfico sintético ([smoke] en el problema). */
  public static boolean isSmokeLead(Lead lead) {
    String problem = lead.getProblem();
    return com.fixy.backend.model.SmokeTraffic.marks(problem);
  }

  private static double tokenSimilarity(String a, String b) {
    java.util.Set<String> ta = new java.util.HashSet<>(java.util.Arrays.asList(a.split("\\s+")));
    java.util.Set<String> tb = new java.util.HashSet<>(java.util.Arrays.asList(b.split("\\s+")));
    ta.remove(""); tb.remove("");
    if (ta.isEmpty() || tb.isEmpty()) {
      return 0.0;
    }
    java.util.Set<String> inter = new java.util.HashSet<>(ta);
    inter.retainAll(tb);
    // Coeficiente de solapamiento (no Jaccard): el repetido típico del 8B es
    // un SUBCONJUNTO del mensaje anterior (misma pregunta, menos preámbulo) —
    // Jaccard lo diluye por la diferencia de largo; overlap lo clava en ~1.0.
    return (double) inter.size() / Math.min(ta.size(), tb.size());
  }

  private static String normalizeForComparison(String text) {
    if (text == null) {
      return "";
    }
    return stripAccents(text.toLowerCase(Locale.ROOT)).replaceAll("[^a-z0-9 ]", "").trim();
  }

  private static String stripAccents(String s) {
    return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
  }
}
