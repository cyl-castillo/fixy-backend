package com.fixy.backend.core.cases;

import com.fixy.backend.model.Lead;

/**
 * Core Fase 3 (ver CORE_FASE3_CONTRATO.md): lo que el loop de turnos y el matching necesitan de un
 * caso (hoy, un {@link Lead}) sin hablar con el repositorio ni con el timeline directamente. Mínimo
 * a propósito: la usan SOLO el loop y el matching; los schedulers y el resto de los servicios
 * siguen hablando con {@code LeadRepository}/{@code LeadTimelineService} hasta que un vertical
 * real justifique moverlos.
 *
 * <p>Una sola implementación hoy ({@code service.LeadCaseService}), sin lógica propia más allá de
 * la validación de atributos contra el playbook.
 */
public interface CaseService {

  /** El caso con ese id; lanza si no existe. */
  Lead get(Long id);

  /** Persiste el caso y devuelve la instancia guardada. */
  Lead save(Lead lead);

  /** Agrega un evento al timeline del caso. */
  void appendEvent(Lead lead, String type, String actor, String message);

  /** true si el caso ya tiene un evento de ese tipo (chequeo de idempotencia). */
  boolean hasEvent(Lead lead, String type);

  /**
   * Fija un atributo libre del caso y lo persiste. Valida contra el playbook: lanza
   * {@link IllegalArgumentException} si la clave no está declarada o el valor es de otro tipo.
   */
  void setAttribute(Lead lead, String name, Object value);

  /** El valor del atributo, o null si el caso no lo tiene. */
  Object attribute(Lead lead, String name);
}
