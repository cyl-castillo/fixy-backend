package com.fixy.backend.controller;

import com.fixy.backend.dto.AgentTurnResponse;
import com.fixy.backend.service.AgentTurnQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Core Fase 3 (pieza 4): qué hizo el agente con cada llamada al LLM de un lead. Basic Auth como el
 * resto de {@code /api/leads/**} (ver SecurityConfig): es un endpoint de ops, no público.
 */
@RestController
@RequestMapping("/api/leads")
public class LeadAgentTurnsController {

  private final AgentTurnQueryService agentTurnQueryService;

  public LeadAgentTurnsController(AgentTurnQueryService agentTurnQueryService) {
    this.agentTurnQueryService = agentTurnQueryService;
  }

  /**
   * Lista cronológica de las llamadas del lead (TURN, INTAKE y GREETING). {@code user_content} y
   * {@code raw_output} salen recortados a 300/600 caracteres salvo {@code ?full=true}.
   */
  @GetMapping("/{id}/agent-turns")
  public List<AgentTurnResponse> agentTurns(
      @PathVariable Long id,
      @RequestParam(defaultValue = "false") boolean full
  ) {
    return agentTurnQueryService.listForLead(id, full);
  }
}
