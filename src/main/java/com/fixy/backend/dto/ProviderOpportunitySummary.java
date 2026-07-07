package com.fixy.backend.dto;

import com.fixy.backend.model.Lead;
import java.time.OffsetDateTime;

/**
 * Oportunidad disponible para un proveedor en su bandeja. A propósito NO
 * incluye teléfono ni nombre completo del cliente — esos datos se ganan
 * recién al aceptar (ver {@code ProviderAssignedLeadSummary}).
 */
public record ProviderOpportunitySummary(
    Long leadId,
    String category,
    String zone,
    String urgency,
    String summary,
    int photoCount,
    OffsetDateTime createdAt
) {
  public static ProviderOpportunitySummary fromEntity(Lead lead, int photoCount) {
    return new ProviderOpportunitySummary(
        lead.getId(),
        lead.getDetectedCategory(),
        lead.getLocation(),
        lead.getUrgency(),
        // OJO privacidad: usamos problem (texto que el cliente escribió sobre
        // SU problema), no lead.getSummary() — el summary heurístico de
        // fallback (AgentService.buildSummary, "Problema de X reportado por
        // {nombre}") concatena el nombre del cliente, lo cual filtraría el
        // dato de contacto que este DTO existe justamente para ocultar.
        lead.getProblem(),
        photoCount,
        lead.getCreatedAt()
    );
  }
}
