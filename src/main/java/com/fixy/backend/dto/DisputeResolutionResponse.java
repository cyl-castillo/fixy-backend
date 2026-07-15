package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/** Respuesta de ops al resolver una disputa (cierre visible, Ola 2). */
public record DisputeResolutionResponse(
    Long leadId,
    boolean disputed,
    String disputeResolutionNote,
    OffsetDateTime disputeResolvedAt
) {
}
