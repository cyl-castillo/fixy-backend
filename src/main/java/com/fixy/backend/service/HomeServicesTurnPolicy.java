package com.fixy.backend.service;

import com.fixy.backend.domain.CategoryDef;
import com.fixy.backend.domain.DomainCatalog;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Core Fase 2 (paso 4/4, ver CORE_FASE2_CONTRATO.md): la implementación de {@link TurnPolicy} para
 * "servicios del hogar" — la ÚNICA con la que hablan el loop de turnos ({@link LeadAgentService}) y
 * el matching ({@link LeadMatchingService}).
 *
 * <p>Antes, esas dos clases llamaban a las funciones puras de {@link HomeServicesPolicy} como
 * estáticos (18 llamadas) y tenían cada una su copia de {@code contactPhoneAlreadyAsked},
 * {@code humanCategory}, {@code safe} y {@code MVP_CATEGORIES}. Ahora todo eso es interfaz
 * ({@link TurnPolicy}) y esta clase la implementa: las guardas puras delegan en los estáticos de
 * {@link HomeServicesPolicy} (que siguen existiendo: los delegadores de test de {@code LeadAgentService}
 * los usan directo), las de historial son las de Fase 1 movidas tal cual, y los helpers duplicados
 * quedan unificados acá.
 *
 * <p>Vive en una clase aparte de {@link HomeServicesPolicy} porque Java no permite que una misma clase
 * declare un método estático y uno de instancia con la misma firma (p. ej. {@code isPriceQuestion(String)}),
 * y los dos tienen que existir: el estático para los tests, el de instancia para la interfaz.
 */
@Service
public class HomeServicesTurnPolicy implements TurnPolicy {

  /** Mismo valor que {@code LeadAgentService.HISTORY_LIMIT}: cuántos
   * mensajes recientes del lead se miran para las guardas de historial. */
  private static final int HISTORY_LIMIT = 10;

  private final LeadMessageService leadMessageService;
  private final AgentService agentService;

  public HomeServicesTurnPolicy(LeadMessageService leadMessageService, AgentService agentService) {
    this.leadMessageService = leadMessageService;
    this.agentService = agentService;
  }

  // ---------------------------------------------------------------------
  // TurnPolicy: necesitan historial de mensajes u otro colaborador
  // (movidas tal cual desde HomeServicesPolicy en Core Fase 2)
  // ---------------------------------------------------------------------

  /** Una zona extraída por el LLM solo se acepta si aparece textualmente
   * (case-insensitive, sin acentos) en algún mensaje del CLIENTE de la
   * conversación reciente. */
  @Override
  public boolean isZoneMentionedByCustomer(Long leadId, String zone) {
    if (zone == null || zone.isBlank()) {
      return false;
    }
    String needle = HomeServicesPolicy.stripAccents(zone.toLowerCase(Locale.ROOT)).trim();
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
        .map(m -> HomeServicesPolicy.stripAccents(m.getText().toLowerCase(Locale.ROOT)))
        .anyMatch(text -> tokens.stream().anyMatch(text::contains));
  }

  private static final java.util.Set<String> ZONE_STOPWORDS =
      java.util.Set.of("de", "del", "la", "las", "el", "los", "san", "santa");

  /** Una categoría extraída por el LLM solo se acepta si el CLIENTE dio algún
   * rastro de ella en sus propios mensajes: o bien
   * {@link DomainCatalog#detectCategory} sobre el
   * texto del cliente devuelve esa misma categoría, o alguna de sus keywords
   * (ver {@link CategoryDef#keywords()}) aparece
   * ahí (sin acentos, case-insensitive). Mismo patrón que
   * isZoneMentionedByCustomer — evita que el LLM le presuma una categoría a
   * un cliente que solo dijo "hola" (leads #116/#119 en prod). */
  @Override
  public boolean isCategoryMentionedByCustomer(Long leadId, String category) {
    if (category == null || category.isBlank()) {
      return false;
    }
    java.util.Optional<CategoryDef> target =
        DomainCatalog.get().categoryById(category);
    if (target.isEmpty()) {
      // Categoría desconocida para el catálogo (no debería pasar dado el enum
      // del schema, pero si pasa no hay nada que validar contra keywords):
      // se rechaza, es más seguro que aceptar algo que no podemos verificar.
      return false;
    }
    String customerText = HomeServicesPolicy.stripAccents(leadMessageService.recentForAgent(leadId, HISTORY_LIMIT).stream()
        .filter(m -> "customer".equals(m.getSender()) && m.getText() != null)
        .map(m -> m.getText().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(" ")));
    if (customerText.isBlank()) {
      return false;
    }
    // 1) Clasificador heurístico laxo sobre el texto del cliente: si coincide
    // con la misma categoría, es la validación más fuerte.
    java.util.Optional<CategoryDef> detected =
        DomainCatalog.get().detectCategory(customerText);
    if (detected.isPresent() && detected.get().id().equals(target.get().id())) {
      return true;
    }
    // 2) Si detectFromText matcheó OTRA categoría primero (la búsqueda es
    // "primer match" en orden del catálogo), igual aceptamos si alguna keyword
    // propia de la categoría extraída aparece en el texto del cliente.
    for (String keyword : target.get().keywords()) {
      if (customerText.contains(HomeServicesPolicy.stripAccents(keyword))) {
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
        if (HomeServicesPolicy.tokenSimilarity(HomeServicesPolicy.normalizeForComparison(m.getText()),
            HomeServicesPolicy.normalizeForComparison(reply)) >= 0.8) {
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
    String cat = HomeServicesPolicy.detectCategoryFromMessages(messages);
    String zone = null;
    String phone = null;
    for (String message : messages) {
      String z = agentService.areaMentionedIn(message);
      if (z != null) {
        zone = z; // la última mención gana, igual que en turnos secuenciales
      }
      if (phone == null) {
        phone = HomeServicesPolicy.phoneMentionedIn(message);
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
  // TurnPolicy: funciones puras — delegan en los estáticos de HomeServicesPolicy
  // ---------------------------------------------------------------------

  @Override
  public boolean isPriceQuestion(String message) {
    return HomeServicesPolicy.isPriceQuestion(message);
  }

  @Override
  public String priceReply(Lead lead) {
    return HomeServicesPolicy.priceReply(lead);
  }

  @Override
  public boolean shouldForceZoneQuestion(
      boolean categoryKnown, String location, String lastCustomerMsg, String reply, boolean zoneArrivedThisTurn) {
    return HomeServicesPolicy.shouldForceZoneQuestion(
        categoryKnown, location, lastCustomerMsg, reply, zoneArrivedThisTurn);
  }

  @Override
  public boolean shouldAskContactPhone(
      boolean categoryKnown, boolean zoneKnown, String currentPhone,
      boolean phoneArrivedThisTurn, String reply) {
    return HomeServicesPolicy.shouldAskContactPhone(
        categoryKnown, zoneKnown, currentPhone, phoneArrivedThisTurn, reply);
  }

  @Override
  public boolean asksForZone(String reply) {
    return HomeServicesPolicy.asksForZone(reply);
  }

  @Override
  public boolean asksForContactPhone(String reply) {
    return HomeServicesPolicy.asksForContactPhone(reply);
  }

  @Override
  public String phoneMentionedIn(String message) {
    return HomeServicesPolicy.phoneMentionedIn(message);
  }

  @Override
  public String detectCategoryFromMessages(List<String> messages) {
    return HomeServicesPolicy.detectCategoryFromMessages(messages);
  }

  @Override
  public boolean isExplicitCorrection(String message) {
    return HomeServicesPolicy.isExplicitCorrection(message);
  }

  @Override
  public Pattern correctionPhrases() {
    return HomeServicesPolicy.correctionPhrases();
  }

  @Override
  public boolean isTrustQuestion(String message) {
    return HomeServicesPolicy.isTrustQuestion(message);
  }

  @Override
  public boolean claimsStillSearching(String reply) {
    return HomeServicesPolicy.claimsStillSearching(reply);
  }

  @Override
  public boolean hasProviderOnTheLine(Lead lead) {
    return HomeServicesPolicy.hasProviderOnTheLine(lead);
  }

  @Override
  public boolean isAcknowledgment(String message) {
    return HomeServicesPolicy.isAcknowledgment(message);
  }

  @Override
  public boolean isAskWhatHappened(String reply) {
    return HomeServicesPolicy.isAskWhatHappened(reply);
  }

  @Override
  public String askWhatHappenedReply() {
    return HomeServicesPolicy.ASK_WHAT_HAPPENED;
  }

  @Override
  public boolean isSmokeLead(Lead lead) {
    return HomeServicesPolicy.isSmokeLead(lead);
  }

  @Override
  public String whatFixyCoversReply() {
    return HomeServicesPolicy.whatFixyCoversReply();
  }

  // ---------------------------------------------------------------------
  // Helpers que LeadAgentService y LeadMatchingService tenían duplicados
  // (cada uno con su "Copia de ...") y que ahora viven una sola vez acá
  // ---------------------------------------------------------------------

  /**
   * Anexa el pedido de WhatsApp a un mensaje del agente si corresponde (sin
   * teléfono en el lead, sin haberlo pedido antes y sin que el mensaje ya lo
   * pida). Para los mensajes de matching, que ya implican categoría+zona resueltas.
   */
  @Override
  public String withContactPhoneAsk(Lead lead, String message) {
    boolean phoneMissing = lead.getPhone() == null || lead.getPhone().isBlank();
    if (phoneMissing && !HomeServicesPolicy.asksForContactPhone(message) && !contactPhoneAlreadyAsked(lead.getId())) {
      return message + " " + HomeServicesPolicy.CONTACT_PHONE_ASK;
    }
    return message;
  }

  /** true si el agente ya pidió el WhatsApp en algún mensaje anterior — se
   * pide UNA vez, no se insiste. */
  @Override
  public boolean contactPhoneAlreadyAsked(Long leadId) {
    try {
      return leadMessageService.recentForAgent(leadId, 30).stream()
          .anyMatch(m -> !"customer".equals(m.getSender())
              && m.getText() != null
              && HomeServicesPolicy.asksForContactPhone(m.getText()));
    } catch (Exception ex) {
      // Ante la duda no repreguntar: molesta más pedir dos veces que no pedir.
      return true;
    }
  }

  /** Deriva del catálogo único DomainCatalog (domain/home-services.yml). */
  @Override
  public String humanCategory(String raw) {
    return DomainCatalog.get().humanLabel(raw);
  }

  @Override
  public String safe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** Fuente única: DomainCatalog (domain/home-services.yml). Espera la categoría ya normalizada (minúscula, sin espacios). */
  @Override
  public boolean isMvpCategory(String category) {
    return MVP_CATEGORIES.contains(category);
  }

  private static final java.util.Set<String> MVP_CATEGORIES =
      java.util.Set.copyOf(DomainCatalog.get().mvpIds());
}
