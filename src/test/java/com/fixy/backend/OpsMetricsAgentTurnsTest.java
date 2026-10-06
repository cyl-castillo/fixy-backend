package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.core.cases.AgentTurn;
import com.fixy.backend.core.cases.AgentTurnRepository;
import com.fixy.backend.dto.OpsDailyMetricsResponse;
import com.fixy.backend.service.OpsMetricsService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core Fase 3 (CORE_FASE3_CONTRATO.md, pieza 4): {@code GET /api/ops/metrics/daily} suma los
 * campos aditivos {@code agentTurns} (solo kind=TURN) e {@code intake} (kind=INTAKE).
 *
 * <p>La ventana de cada test es de 2031: ninguna otra fila de la base (de otros tests o de hilos
 * {@code @Async}) puede caer ahí, así que los conteos son exactos sin asertar jamás contra el
 * total absoluto de la tabla.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpsMetricsAgentTurnsTest {

  private static final OffsetDateTime FROM = OffsetDateTime.of(2031, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime TO = FROM.plusDays(1);

  @Autowired private OpsMetricsService opsMetricsService;
  @Autowired private AgentTurnRepository agentTurnRepository;
  @Autowired private MockMvc mockMvc;

  private void seed(String kind, String outcome, String guards, OffsetDateTime createdAt) {
    AgentTurn turn = new AgentTurn();
    turn.setKind(kind);
    turn.setProvider("openai");
    turn.setPromptVersion("abcdef012345");
    turn.setUserContent("contenido");
    turn.setLatencyMs(10);
    turn.setOutcome(outcome);
    turn.setGuards(guards);
    turn.setCreatedAt(createdAt);
    agentTurnRepository.save(turn);
  }

  private void seedWindow() {
    OffsetDateTime inside = FROM.plusHours(5);
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_OK, "", inside);
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_OK, "zone_discarded", inside);
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_EMPTY, "", inside);
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_NO_REPLY, "", inside);
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_FALLBACK_REPEAT, "", inside);
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_FALLBACK_ZONE_QUESTION, "", inside);
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_ERROR_FALLBACK, "", inside);
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_ESCALATED, "", inside);
    // Escalada por la heurística: el outcome es de fallback Y cuenta como escalado por su guard.
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_FALLBACK_REPEAT, "short_answer_recorded,heuristic_escalated", inside);
    // Ni LLM_OK ni fallback: cuentan en total y en byOutcome.
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_DETERMINISTIC, "", inside);
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_RAW_POSTED, "", inside);
    // INTAKE y GREETING no entran en agentTurns.
    seed(AgentTurn.KIND_INTAKE, AgentTurn.OUTCOME_HEURISTIC, "", inside);
    seed(AgentTurn.KIND_INTAKE, AgentTurn.OUTCOME_HEURISTIC, "", inside);
    seed(AgentTurn.KIND_INTAKE, AgentTurn.OUTCOME_LLM_OK, "", inside);
    seed(AgentTurn.KIND_GREETING, AgentTurn.OUTCOME_LLM_OK, "", inside);
    // Fuera de la ventana: justo antes de FROM y justo en TO (exclusivo).
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_OK, "", FROM.minusSeconds(1));
    seed(AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_OK, "", TO);
    seed(AgentTurn.KIND_INTAKE, AgentTurn.OUTCOME_HEURISTIC, "", TO);
  }

  @Test
  void agentTurnsCuentaSoloTurnsDeLaVentana() {
    seedWindow();

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(FROM, TO);

    OpsDailyMetricsResponse.AgentTurnsSummary turns = metrics.agentTurns();
    assertThat(turns.total()).isEqualTo(11);
    assertThat(turns.llmOk()).isEqualTo(2);
    // LLM_EMPTY + LLM_NO_REPLY + 2 FALLBACK_REPEAT + FALLBACK_ZONE_QUESTION + ERROR_FALLBACK
    assertThat(turns.fallback()).isEqualTo(6);
    // LLM_ESCALATED + el turno con guard heuristic_escalated
    assertThat(turns.escalated()).isEqualTo(2);
    assertThat(turns.byOutcome())
        .containsEntry("LLM_OK", 2L)
        .containsEntry("LLM_EMPTY", 1L)
        .containsEntry("LLM_NO_REPLY", 1L)
        .containsEntry("FALLBACK_REPEAT", 2L)
        .containsEntry("FALLBACK_ZONE_QUESTION", 1L)
        .containsEntry("ERROR_FALLBACK", 1L)
        .containsEntry("LLM_ESCALATED", 1L)
        .containsEntry("DETERMINISTIC", 1L)
        .containsEntry("LLM_RAW_POSTED", 1L)
        .hasSize(9);
  }

  @Test
  void intakeCuentaSoloIntakeDeLaVentana() {
    seedWindow();

    OpsDailyMetricsResponse.IntakeSummary intake = opsMetricsService.dailyMetrics(FROM, TO).intake();

    assertThat(intake.total()).isEqualTo(3);
    assertThat(intake.heuristic()).isEqualTo(2);
  }

  @Test
  void unaVentanaSinFilasDevuelveCeros() {
    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(FROM.plusYears(1), TO.plusYears(1));

    assertThat(metrics.agentTurns().total()).isZero();
    assertThat(metrics.agentTurns().escalated()).isZero();
    assertThat(metrics.agentTurns().byOutcome()).isEmpty();
    assertThat(metrics.intake().total()).isZero();
  }

  @Test
  void elEndpointExponeLosCamposAditivosYNoCambiaLosExistentes() throws Exception {
    seedWindow();

    mockMvc.perform(get("/api/ops/metrics/daily")
            .param("from", "2031-03-01").param("to", "2031-03-02")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agentTurns.total").value(11))
        .andExpect(jsonPath("$.agentTurns.llmOk").value(2))
        .andExpect(jsonPath("$.agentTurns.fallback").value(6))
        .andExpect(jsonPath("$.agentTurns.escalated").value(2))
        .andExpect(jsonPath("$.agentTurns.byOutcome.LLM_OK").value(2))
        .andExpect(jsonPath("$.intake.total").value(3))
        .andExpect(jsonPath("$.intake.heuristic").value(2))
        // Lo de siempre sigue ahí.
        .andExpect(jsonPath("$.totalLeadsCreated").exists())
        .andExpect(jsonPath("$.funnel").exists())
        .andExpect(jsonPath("$.gates").exists());
  }
}
