package com.fixy.backend.dto;

import com.fixy.backend.model.LeadStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record LeadResponse(
    Long id,
    String name,
    String phone,
    String problem,
    String detectedCategory,
    String urgency,
    String location,
    String summary,
    List<String> missingFields,
    List<String> blockingFields,
    boolean readyForMatching,
    String nextRecommendedAction,
    String assignedProvider,
    String notes,
    String history,
    LeadStatus status,
    String suggestedReply,
    String agentSource,
    String accessToken,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    boolean disputed,
    OffsetDateTime disputeResolvedAt,
    String disputeResolutionNote,
    AssignedProviderSummary assignedProviderSummary
) {
  /**
   * Datos públicos del proveedor asignado a este lead, para que el cliente
   * vea con quién está tratando (contrato acordado con el agente que
   * construye la superficie del cliente). Null si todavía no hay proveedor
   * asignado. {@code ratingAverage} es null si {@code ratingCount == 0}
   * (misma regla de honestidad que {@link ProviderPublicPreview}: no
   * mostrar un promedio inflado a un proveedor sin calificaciones reales).
   */
  public record AssignedProviderSummary(
      String name,
      Double ratingAverage,
      Integer ratingCount,
      Integer completedJobs,
      String primaryZone,
      /** Teléfono del proveedor asignado — botón Llamar del cliente (mejoras UX 2026-08). */
      String phone,
      /** Últimas reseñas CON TEXTO de este proveedor (máx 2, anónimas). */
      java.util.List<ReviewSnippet> recentReviews
  ) {
  }

  /** Reseña pública anónima: solo estrella y texto (mejoras UX 2026-08). */
  public record ReviewSnippet(Integer score, String comment) {
  }
}
