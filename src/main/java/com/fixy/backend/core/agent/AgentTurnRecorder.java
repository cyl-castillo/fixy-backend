package com.fixy.backend.core.agent;

import com.fixy.backend.core.cases.AgentTurn;
import com.fixy.backend.core.cases.AgentTurnRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Core Fase 3 (ver CORE_FASE3_CONTRATO.md, pieza 3): deja cada llamada al LLM —o la decisión de no
 * llamarlo— como una fila de {@code agent_turns} con su veredicto. Antes la decisión
 * "LLM vs fallback heurístico" solo vivía en líneas de log, y un mes de turnos cayendo a fallback
 * pasó inadvertido.
 *
 * <p>Uso: {@link #start} (solo en memoria: toma el reloj, NO inserta — con {@code outcome} NOT NULL
 * un insert al arrancar violaría la columna y el WARN se tragaría la fila) y exactamente un
 * {@link #finish} por llamada, que hace UN {@code save}. {@code finish} es idempotente.
 *
 * <p>Sin transacción propia, a propósito: igual que {@code LeadTimelineService.appendEvent}, es un
 * bean aparte con un {@code save} de propagación default (cada save commitea solo, porque el loop
 * del turno no es transaccional). Prohibido {@code REQUIRES_NEW}: en los tests con
 * {@code @Transactional} de clase, las filas commiteadas fuera del rollback contaminarían el
 * contexto cacheado. Registrar NUNCA rompe el turno: todo {@code RuntimeException} se traga con WARN.
 *
 * <p>Una instancia {@link #noop()} (repositorio y gateway null) la usan los constructores legacy de
 * los servicios, que existen para los tests: tolera todo y no escribe nada.
 */
@Service
public class AgentTurnRecorder {

  private static final Logger log = LoggerFactory.getLogger(AgentTurnRecorder.class);

  private static final int MAX_CONTENT = 16000;
  private static final int MAX_GUARDS = 400;
  private static final int MAX_OUTCOME = 40;
  private static final int MAX_MODEL = 80;
  private static final int MAX_PROVIDER = 32;
  private static final int MAX_PROMPT_VERSION = 64;

  /** Veredictos en los que no se llamó al LLM: provider {@code none}, sin modelo. */
  private static final Set<String> NO_LLM_OUTCOMES =
      Set.of(AgentTurn.OUTCOME_DETERMINISTIC, AgentTurn.OUTCOME_HEURISTIC);

  /** null = no-op (constructores legacy de los tests). */
  private final AgentTurnRepository repository;
  /** null = provider {@code none} (tests legacy). */
  private final LlmGateway llmGateway;

  @Autowired
  public AgentTurnRecorder(AgentTurnRepository repository, LlmGateway llmGateway) {
    this.repository = repository;
    this.llmGateway = llmGateway;
  }

  /** Recorder que no escribe nada y tolera {@code null} en todo: para los constructores legacy. */
  public static AgentTurnRecorder noop() {
    return new AgentTurnRecorder(null, null);
  }

  /**
   * sha256 (12 hex) de una PLANTILLA de prompt, para saber con qué versión del prompt se generó
   * cada fila. Siempre de la plantilla, nunca del render: el render lleva datos del cliente.
   */
  public static String promptVersion(String template) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((template == null ? "" : template).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 6; i++) {
        hex.append(String.format("%02x", digest[i]));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 no disponible", ex);
    }
  }

  /** Arranca la medición de una llamada. Solo en memoria: no toca la base. */
  public Handle start(String kind, Long leadId, String promptVersion, String userContent) {
    return new Handle(kind, leadId, promptVersion, userContent, System.nanoTime());
  }

  /**
   * Cierra la llamada con UN {@code save}. Idempotente (la segunda llamada es no-op) y tolerante:
   * handle null, repositorio null o cualquier {@code RuntimeException} al guardar → no hace nada o
   * WARN, jamás rompe al que llama.
   */
  public void finish(Handle handle, String rawOutput, String outcome, Collection<String> guards) {
    if (handle == null || !handle.finished.compareAndSet(false, true) || repository == null) {
      return;
    }
    try {
      String effectiveOutcome = outcome == null || outcome.isBlank() ? AgentTurn.OUTCOME_ERROR_FALLBACK : outcome;
      boolean noLlm = NO_LLM_OUTCOMES.contains(effectiveOutcome);
      AgentTurn row = new AgentTurn();
      row.setLeadId(handle.leadId);
      row.setKind(handle.kind);
      row.setProvider(noLlm ? "none" : providerName());
      row.setModel(noLlm ? null : truncate(modelName(), MAX_MODEL));
      row.setPromptVersion(truncate(handle.promptVersion == null ? "" : handle.promptVersion, MAX_PROMPT_VERSION));
      row.setUserContent(truncate(handle.userContent == null ? "" : handle.userContent, MAX_CONTENT));
      row.setRawOutput(truncate(rawOutput, MAX_CONTENT));
      row.setLatencyMs(handle.elapsedMs());
      row.setOutcome(truncate(effectiveOutcome, MAX_OUTCOME));
      row.setGuards(truncate(joinGuards(guards), MAX_GUARDS));
      row.setCreatedAt(OffsetDateTime.now());
      repository.save(row);
    } catch (RuntimeException ex) {
      log.warn("agent_turns: no se pudo registrar la llamada (kind={}, lead={}, outcome={}): {}",
          handle.kind, handle.leadId, outcome, ex.getMessage());
    }
  }

  private String providerName() {
    String provider = llmGateway == null ? null : llmGateway.provider();
    return provider == null || provider.isBlank() ? "none" : truncate(provider, MAX_PROVIDER);
  }

  private String modelName() {
    return llmGateway == null ? null : llmGateway.model();
  }

  private static String joinGuards(Collection<String> guards) {
    if (guards == null || guards.isEmpty()) {
      return "";
    }
    return String.join(",", new LinkedHashSet<>(guards));
  }

  private static String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  /** Una llamada en curso: lo que {@link #start} captura y {@link #finish} necesita. */
  public static final class Handle {
    private final String kind;
    private final Long leadId;
    private final String promptVersion;
    private final String userContent;
    private final long startNanos;
    private volatile long stopNanos;
    private final AtomicBoolean finished = new AtomicBoolean();

    private Handle(String kind, Long leadId, String promptVersion, String userContent, long startNanos) {
      this.kind = kind;
      this.leadId = leadId;
      this.promptVersion = promptVersion;
      this.userContent = userContent;
      this.startNanos = startNanos;
    }

    /**
     * Congela la latencia en este instante (idempotente: gana la primera llamada). Se llama apenas
     * vuelve el LLM, para que {@code latency_ms} mida la llamada y no lo que el loop hace después
     * (guards, posteo, y el fallback, que puede llamar al clasificador de intake). Sin esta llamada
     * la latencia se mide hasta {@link AgentTurnRecorder#finish}.
     */
    public void stopClock() {
      if (stopNanos == 0) {
        stopNanos = System.nanoTime();
      }
    }

    private int elapsedMs() {
      long end = stopNanos != 0 ? stopNanos : System.nanoTime();
      long ms = Math.max(0, end - startNanos) / 1_000_000;
      return (int) Math.min(Integer.MAX_VALUE, ms);
    }
  }
}
