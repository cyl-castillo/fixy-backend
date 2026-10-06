package com.fixy.backend.service;

import com.fixy.backend.core.cases.AgentTurn;
import com.fixy.backend.core.cases.AgentTurnRepository;
import com.fixy.backend.dto.AgentTurnResponse;
import com.fixy.backend.repository.LeadRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Core Fase 3 (pieza 4): lectura de {@code agent_turns} para ops. Solo lectura; la escritura es
 * de {@code AgentTurnRecorder}.
 */
@Service
public class AgentTurnQueryService {

  /** Recorte de {@code userContent} en la lista (el contenido completo va con {@code ?full=true}). */
  static final int USER_CONTENT_PREVIEW = 300;
  /** Recorte de {@code rawOutput} en la lista. */
  static final int RAW_OUTPUT_PREVIEW = 600;

  private final AgentTurnRepository agentTurnRepository;
  private final LeadRepository leadRepository;

  public AgentTurnQueryService(AgentTurnRepository agentTurnRepository, LeadRepository leadRepository) {
    this.agentTurnRepository = agentTurnRepository;
    this.leadRepository = leadRepository;
  }

  /** Cronológico, incluidas las filas INTAKE con el id de este lead. 404 si el lead no existe. */
  public List<AgentTurnResponse> listForLead(Long leadId, boolean full) {
    if (!leadRepository.existsById(leadId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found");
    }
    return agentTurnRepository.findByLeadIdOrderByCreatedAtAscIdAsc(leadId).stream()
        .map(turn -> toResponse(turn, full))
        .toList();
  }

  private static AgentTurnResponse toResponse(AgentTurn turn, boolean full) {
    return new AgentTurnResponse(
        turn.getId(),
        turn.getKind(),
        turn.getProvider(),
        turn.getModel(),
        turn.getPromptVersion(),
        full ? turn.getUserContent() : preview(turn.getUserContent(), USER_CONTENT_PREVIEW),
        full ? turn.getRawOutput() : preview(turn.getRawOutput(), RAW_OUTPUT_PREVIEW),
        turn.getLatencyMs() == null ? 0 : turn.getLatencyMs(),
        turn.getOutcome(),
        guardList(turn.getGuards()),
        turn.getCreatedAt());
  }

  private static String preview(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }

  private static List<String> guardList(String csv) {
    return csv == null || csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
  }
}
