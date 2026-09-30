package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Core Fase 1 (paso 2/3, ver CORE_FASE1_CONTRATO.md) y Fase 2 (paso 4/4, ver CORE_FASE2_CONTRATO.md):
 * lo que el loop de turnos ({@link LeadAgentService}) y el matching ({@link LeadMatchingService})
 * necesitan del dominio para decidir un turno. En Fase 1 solo estaban acá las guardas que miran
 * HISTORIAL del lead; las funciones puras se llamaban como estáticos de {@link HomeServicesPolicy}.
 * Desde Fase 2 TODO lo que el loop y el matching le piden al dominio pasa por esta interfaz —guardas
 * puras incluidas— y los helpers que tenían duplicados (pedido de WhatsApp ya hecho, etiqueta humana
 * de la categoría, categoría MVP, {@code safe}) quedan unificados en la implementación.
 *
 * <p>Una sola implementación hoy: {@link HomeServicesTurnPolicy} (heurísticas de "servicios del hogar" —
 * el vertical único de Fixy), que delega las funciones puras en los estáticos de
 * {@link HomeServicesPolicy}. La interfaz existe para que el loop no dependa de vocabulario de ese
 * vertical, no porque haya o se planee una segunda implementación en esta fase.
 */
public interface TurnPolicy {

  /** true si {@code zone} aparece textualmente (case/acento-insensitive) en
   * algún mensaje del CLIENTE de la conversación reciente del lead. */
  boolean isZoneMentionedByCustomer(Long leadId, String zone);

  /** true si el CLIENTE dio algún rastro de {@code category} en sus propios
   * mensajes recientes (heurística de clasificación o keyword suelta). */
  boolean isCategoryMentionedByCustomer(Long leadId, String category);

  /** true si {@code reply} es (normalizado) igual a algún mensaje reciente
   * que el agente ya le mandó a este lead — señal de LLM en loop. */
  boolean isStuckRepeatingItself(Long leadId, String reply);

  /** true si algún mensaje reciente del CLIENTE de este lead trae la marca
   * de tráfico sintético ([smoke]). */
  boolean customerMentionedSmoke(Long leadId);

  /** Categoría/zona/teléfono que los mensajes del CLIENTE mencionan
   * explícitamente pisan lo extraído por el LLM (base determinista de las
   * correcciones "me equivoqué"). Devuelve {@code extracted} sin tocar si
   * ningún mensaje trae señales nuevas. */
  Map<String, String> withMessageSignals(List<String> messages, Map<String, String> extracted);

  // ---------------------------------------------------------------------
  // Guardas puras (Fase 2: antes eran estáticos de HomeServicesPolicy)
  // ---------------------------------------------------------------------

  /** true si el mensaje del cliente es una pregunta de precio (fallback sin LLM). */
  boolean isPriceQuestion(String message);

  /** Respuesta del fallback heurístico a una pregunta de precio (rango, o "lo confirma el proveedor"). */
  String priceReply(Lead lead);

  /** true si la respuesta del LLM debe reemplazarse por la pregunta determinista de zona. */
  boolean shouldForceZoneQuestion(
      boolean categoryKnown, String location, String lastCustomerMsg, String reply, boolean zoneArrivedThisTurn);

  /** true si corresponde anexar el pedido de WhatsApp a la respuesta del turno. */
  boolean shouldAskContactPhone(
      boolean categoryKnown, boolean zoneKnown, String currentPhone,
      boolean phoneArrivedThisTurn, String reply);

  /** true si la respuesta menciona la zona/ubicación como pregunta o pedido (insensible a acentos). */
  boolean asksForZone(String reply);

  /** true si la respuesta ya pide teléfono/WhatsApp (insensible a acentos). */
  boolean asksForContactPhone(String reply);

  /** Teléfono uruguayo detectado por regex en el texto del cliente, o null. */
  String phoneMentionedIn(String message);

  /** Categoría detectada sobre una tanda de mensajes pendientes (semántica secuencial), o null. */
  String detectCategoryFromMessages(List<String> messages);

  /** true si el mensaje trae intención explícita de corrección ("me equivoqué", "en realidad es..."). */
  boolean isExplicitCorrection(String message);

  /** El patrón de las frases explícitas de corrección. */
  Pattern correctionPhrases();

  /** true si el mensaje es una pregunta de confianza/seguridad sobre quién viene a la casa. */
  boolean isTrustQuestion(String message);

  /** true si la respuesta afirma que todavía se busca proveedor (falso si el pedido ya tiene uno encima). */
  boolean claimsStillSearching(String reply);

  /** true si el pedido ya tiene un proveedor concreto encima (contactado o asignado). */
  boolean hasProviderOnTheLine(Lead lead);

  /** true si el mensaje es un cierre/asentimiento corto ("ok", "gracias"). */
  boolean isAcknowledgment(String message);

  /** true si la respuesta es la repregunta genérica de arranque, sola o precedida por el ack de la zona. */
  boolean isAskWhatHappened(String reply);

  /** La repregunta genérica de arranque (constante: el guard del fallback la compara por contenido). */
  String askWhatHappenedReply();

  /** true si el pedido está marcado como tráfico sintético ([smoke] en el problema). */
  boolean isSmokeLead(Lead lead);

  /** Qué consigue Fixy HOY, en una frase (lo que recibe el vecino cuyo pedido no se entendió). */
  String whatFixyCoversReply();

  // ---------------------------------------------------------------------
  // Helpers unificados (Fase 2: antes duplicados en LeadAgentService y LeadMatchingService)
  // ---------------------------------------------------------------------

  /** Anexa el pedido de WhatsApp al mensaje si el lead no tiene teléfono y no se pidió antes. */
  String withContactPhoneAsk(Lead lead, String message);

  /** true si el agente ya pidió el WhatsApp en algún mensaje anterior (se pide UNA vez). */
  boolean contactPhoneAlreadyAsked(Long leadId);

  /** Nombre humano en español de la categoría; "tu pedido" si no se reconoce el id. */
  String humanCategory(String raw);

  /** {@code value}, o {@code fallback} si es null o blank. */
  String safe(String value, String fallback);

  /** true si la categoría (ya normalizada: minúscula, sin espacios) participa en matching real. */
  boolean isMvpCategory(String category);
}
