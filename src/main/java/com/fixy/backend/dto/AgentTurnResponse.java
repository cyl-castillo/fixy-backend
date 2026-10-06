package com.fixy.backend.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Una llamada al LLM (o la decisión de no llamarlo) de un lead, para ops
 * ({@code GET /api/leads/{id}/agent-turns}). {@code userContent} y {@code rawOutput} vienen
 * recortados (300/600 caracteres) salvo {@code ?full=true}. Core Fase 3, ver CORE_FASE3_CONTRATO.md.
 *
 * @param kind   TURN | INTAKE | GREETING
 * @param outcome el veredicto (LLM_OK, LLM_EMPTY, FALLBACK_REPEAT, DETERMINISTIC, ...)
 * @param guards  los guards deterministas que se activaron en la llamada
 */
public record AgentTurnResponse(
    Long id,
    String kind,
    String provider,
    String model,
    String promptVersion,
    String userContent,
    String rawOutput,
    int latencyMs,
    String outcome,
    List<String> guards,
    OffsetDateTime createdAt
) {
}
