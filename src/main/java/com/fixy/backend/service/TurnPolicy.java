package com.fixy.backend.service;

import java.util.List;
import java.util.Map;

/**
 * Core Fase 1 (paso 2/3, ver CORE_FASE1_CONTRATO.md): lo que el loop de
 * turnos ({@link LeadAgentService}) necesita del dominio para decidir un
 * turno, cuando esa decisión necesita mirar HISTORIAL del lead (mensajes
 * previos) y no solo el mensaje/reply del turno actual. Las guardas
 * deterministas que son funciones puras (sin historial ni otros
 * colaboradores) no están acá — viven como estáticos públicos en
 * {@link HomeServicesPolicy} y se llaman directo, sin pasar por esta
 * interfaz (no ganan nada con la indirección de una instancia).
 *
 * <p>Una sola implementación hoy: {@link HomeServicesPolicy} (heurísticas de
 * "servicios del hogar" — el vertical único de Fixy). La interfaz existe para
 * que el loop no dependa de vocabulario de ese vertical, no porque haya o se
 * planee una segunda implementación en esta fase.
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
}
