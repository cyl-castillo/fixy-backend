package com.fixy.backend;

import static org.hamcrest.Matchers.hasLength;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.core.cases.AgentTurn;
import com.fixy.backend.core.cases.AgentTurnRepository;
import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.LeadRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core Fase 3 (CORE_FASE3_CONTRATO.md, pieza 4): {@code GET /api/leads/{id}/agent-turns} — Basic
 * Auth, lista cronológica con las filas INTAKE del lead, y recorte de user_content/raw_output
 * (300/600) salvo {@code ?full=true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LeadAgentTurnsEndpointTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private AgentTurnRepository agentTurnRepository;

  private Lead lead() {
    Lead lead = new Lead();
    lead.setProblem("[smoke] pedido de prueba del endpoint de agent_turns");
    lead.setChannel("web-chat");
    lead.setAccessToken("tok-agent-turns-endpoint-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  private AgentTurn turn(Long leadId, String kind, String outcome, String userContent, String rawOutput,
      String guards, OffsetDateTime createdAt) {
    AgentTurn turn = new AgentTurn();
    turn.setLeadId(leadId);
    turn.setKind(kind);
    turn.setProvider("openai");
    turn.setModel("modelo-de-prueba");
    turn.setPromptVersion("abcdef012345");
    turn.setUserContent(userContent);
    turn.setRawOutput(rawOutput);
    turn.setLatencyMs(42);
    turn.setOutcome(outcome);
    turn.setGuards(guards);
    turn.setCreatedAt(createdAt);
    return agentTurnRepository.save(turn);
  }

  @Test
  void sinCredencialesDevuelve401() throws Exception {
    mockMvc.perform(get("/api/leads/{id}/agent-turns", lead().getId()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unLeadInexistenteDevuelve404() throws Exception {
    mockMvc.perform(get("/api/leads/{id}/agent-turns", 987_654_321L).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isNotFound());
  }

  @Test
  void unLeadSinLlamadasDevuelveListaVacia() throws Exception {
    mockMvc.perform(get("/api/leads/{id}/agent-turns", lead().getId()).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void listaCronologicaConLasFilasIntakeDelLeadYSusGuards() throws Exception {
    Lead lead = lead();
    Lead otro = lead();
    OffsetDateTime now = OffsetDateTime.now();
    // Insertadas a propósito fuera de orden: la respuesta es cronológica, no por id.
    turn(lead.getId(), AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_OK, "segundo", "r2", "zone_discarded,short_answer_recorded", now.minusMinutes(1));
    turn(lead.getId(), AgentTurn.KIND_INTAKE, AgentTurn.OUTCOME_HEURISTIC, "primero", null, "", now.minusMinutes(3));
    turn(lead.getId(), AgentTurn.KIND_GREETING, AgentTurn.OUTCOME_LLM_EMPTY, "tercero", null, "", now.minusMinutes(2));
    turn(otro.getId(), AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_OK, "de otro lead", "x", "", now);

    mockMvc.perform(get("/api/leads/{id}/agent-turns", lead.getId()).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].kind").value("INTAKE"))
        .andExpect(jsonPath("$[0].outcome").value("HEURISTIC"))
        .andExpect(jsonPath("$[0].userContent").value("primero"))
        .andExpect(jsonPath("$[0].guards.length()").value(0))
        .andExpect(jsonPath("$[1].kind").value("GREETING"))
        .andExpect(jsonPath("$[2].kind").value("TURN"))
        .andExpect(jsonPath("$[2].provider").value("openai"))
        .andExpect(jsonPath("$[2].model").value("modelo-de-prueba"))
        .andExpect(jsonPath("$[2].promptVersion").value("abcdef012345"))
        .andExpect(jsonPath("$[2].latencyMs").value(42))
        .andExpect(jsonPath("$[2].guards[0]").value("zone_discarded"))
        .andExpect(jsonPath("$[2].guards[1]").value("short_answer_recorded"));
  }

  @Test
  void elContenidoSeRecortaA300Y600SalvoFullTrue() throws Exception {
    Lead lead = lead();
    turn(lead.getId(), AgentTurn.KIND_TURN, AgentTurn.OUTCOME_LLM_OK,
        "u".repeat(1000), "r".repeat(2000), "", OffsetDateTime.now());

    mockMvc.perform(get("/api/leads/{id}/agent-turns", lead.getId()).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].userContent").value(hasLength(300)))
        .andExpect(jsonPath("$[0].rawOutput").value(hasLength(600)));

    mockMvc.perform(get("/api/leads/{id}/agent-turns", lead.getId()).param("full", "true")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].userContent").value(hasLength(1000)))
        .andExpect(jsonPath("$[0].rawOutput").value(hasLength(2000)));
  }

  @Test
  void unRawOutputNullSigueSiendoNull() throws Exception {
    Lead lead = lead();
    turn(lead.getId(), AgentTurn.KIND_TURN, AgentTurn.OUTCOME_DETERMINISTIC, "gracias", null, "", OffsetDateTime.now());

    mockMvc.perform(get("/api/leads/{id}/agent-turns", lead.getId()).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].rawOutput").doesNotExist());
  }
}
