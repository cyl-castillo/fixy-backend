package com.fixy.backend.core.cases;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Core Fase 3 (ver CORE_FASE3_CONTRATO.md, pieza 3): una llamada al LLM —o la decisión de NO
 * llamarlo— con su veredicto. La escribe {@code AgentTurnRecorder}; la leen ops
 * ({@code GET /api/leads/{id}/agent-turns}) y la métrica diaria. Sin FK a {@code leads}: el
 * {@code leadId} es null para un intake sin lead y los leads de prueba se borran a mano.
 *
 * <p>Las constantes de abajo son el vocabulario fijo de {@code kind}, {@code outcome} y
 * {@code guards}: quien escribe y quien lee usan las mismas.
 */
@Entity
@Table(name = "agent_turns", indexes = {
    @Index(name = "ix_agent_turns_lead_created", columnList = "lead_id, created_at"),
    @Index(name = "ix_agent_turns_created", columnList = "created_at")
})
public class AgentTurn {

  public static final String KIND_TURN = "TURN";
  public static final String KIND_INTAKE = "INTAKE";
  public static final String KIND_GREETING = "GREETING";

  /** El turno no llamó al LLM (ack en espera, pregunta de precio). */
  public static final String OUTCOME_DETERMINISTIC = "DETERMINISTIC";
  public static final String OUTCOME_LLM_OK = "LLM_OK";
  /** El gateway devolvió null/blank. */
  public static final String OUTCOME_LLM_EMPTY = "LLM_EMPTY";
  /** Hubo JSON pero sin {@code reply}. */
  public static final String OUTCOME_LLM_NO_REPLY = "LLM_NO_REPLY";
  /** No se encontró JSON (o el parseo lanzó) y se posteó el texto crudo del LLM. */
  public static final String OUTCOME_LLM_RAW_POSTED = "LLM_RAW_POSTED";
  /** La acción pedida por el LLM fue {@code escalate}; gana sobre {@link #OUTCOME_LLM_OK}. */
  public static final String OUTCOME_LLM_ESCALATED = "LLM_ESCALATED";
  public static final String OUTCOME_FALLBACK_REPEAT = "FALLBACK_REPEAT";
  public static final String OUTCOME_FALLBACK_SEARCHING_CLAIM = "FALLBACK_SEARCHING_CLAIM";
  public static final String OUTCOME_FALLBACK_ZONE_QUESTION = "FALLBACK_ZONE_QUESTION";
  /** El LLM prometió gestionar/buscar con el pedido SIN categoría matcheable: se posteó la pregunta de rubro. */
  public static final String OUTCOME_FALLBACK_NO_CATEGORY = "FALLBACK_NO_CATEGORY";
  public static final String OUTCOME_ERROR_FALLBACK = "ERROR_FALLBACK";
  /** INTAKE: hubo respuesta del LLM pero no se pudo parsear. */
  public static final String OUTCOME_LLM_UNPARSEABLE = "LLM_UNPARSEABLE";
  /** INTAKE: sin LLM configurado, clasificó la heurística. */
  public static final String OUTCOME_HEURISTIC = "HEURISTIC";

  public static final String GUARD_ZONE_DISCARDED = "zone_discarded";
  public static final String GUARD_CATEGORY_DISCARDED = "category_discarded";
  public static final String GUARD_MESSAGE_SIGNALS_OVERRIDE = "message_signals_override";
  public static final String GUARD_CONTACT_PHONE_ASK_APPENDED = "contact_phone_ask_appended";
  public static final String GUARD_SHORT_ANSWER_RECORDED = "short_answer_recorded";
  public static final String GUARD_HEURISTIC_ESCALATED = "heuristic_escalated";
  public static final String GUARD_PROMISE_WITHOUT_CATEGORY = "promise_without_category";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Null: intake sin lead. Nunca el id de un provider_lead. */
  private Long leadId;

  @Column(nullable = false, length = 16)
  private String kind;

  /** El valor de {@code LlmGateway.provider()}: openai | workersai | ollama | none. */
  @Column(nullable = false, length = 32)
  private String provider;

  @Column(length = 80)
  private String model;

  /** sha256 (12 hex) de la PLANTILLA del prompt, no del render con datos del cliente. */
  @Column(nullable = false, length = 64)
  private String promptVersion;

  // length explícito: sin él H2 crea varchar(255) y LANZA 22001 en un turno largo, y el recorder
  // (que traga RuntimeException) perdería la fila en silencio.
  @Column(nullable = false, length = 16000)
  private String userContent = "";

  @Column(length = 16000)
  private String rawOutput;

  /** Integer, no Long: con ddl-auto=validate un Long contra la columna integer no arranca. */
  @Column(nullable = false)
  private Integer latencyMs = 0;

  @Column(nullable = false, length = 40)
  private String outcome;

  /** CSV de nombres fijos ({@code GUARD_*}). */
  @Column(nullable = false, length = 400)
  private String guards = "";

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
    if (userContent == null) {
      userContent = "";
    }
    if (guards == null) {
      guards = "";
    }
    if (latencyMs == null) {
      latencyMs = 0;
    }
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getLeadId() { return leadId; }
  public void setLeadId(Long leadId) { this.leadId = leadId; }
  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }
  public String getProvider() { return provider; }
  public void setProvider(String provider) { this.provider = provider; }
  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }
  public String getPromptVersion() { return promptVersion; }
  public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
  public String getUserContent() { return userContent; }
  public void setUserContent(String userContent) { this.userContent = userContent; }
  public String getRawOutput() { return rawOutput; }
  public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }
  public Integer getLatencyMs() { return latencyMs; }
  public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
  public String getOutcome() { return outcome; }
  public void setOutcome(String outcome) { this.outcome = outcome; }
  public String getGuards() { return guards; }
  public void setGuards(String guards) { this.guards = guards; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
