package com.fixy.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** DTOs de Fixy Barrio (B1, BARRIO_B1_CONTRATO.md). Los públicos NUNCA llevan teléfono ni descripción. */
public final class BarrioDtos {

  private BarrioDtos() {
  }

  /** Objeto público de un reclamo (solo PUBLISHED / RESOLVED). Coordenadas redondeadas a 4 decimales. */
  public record PublicReport(
      Long id,
      String category,
      String categoryLabel,
      String zone,
      double lat,
      double lng,
      String photoUrl,
      OffsetDateTime createdAt,
      long daysOpen,
      int supporters,
      String status,
      OffsetDateTime resolvedAt,
      Integer daysToResolve,
      String closurePhotoUrl
  ) {}

  public record CreateResponse(Long id, String status, Long possibleDuplicateOf) {}

  public record ClosureResponse(String status) {}

  public record CategoryCount(long open, long resolved) {}

  public record ZoneCount(String zone, long open, long resolved) {}

  public record Stats(
      long open,
      long resolved,
      double avgDaysOpen,
      Map<String, CategoryCount> byCategory,
      List<ZoneCount> byZone
  ) {}

  /** Vista de ops: lo público + descripción, teléfono enmascarado y canal. */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record OpsReport(
      Long id,
      String category,
      String categoryLabel,
      String zone,
      double lat,
      double lng,
      String photoUrl,
      OffsetDateTime createdAt,
      long daysOpen,
      int supporters,
      String status,
      String description,
      String reporterPhone,
      String channel,
      Long possibleDuplicateOf,
      OpsAi ai
  ) {}

  /** Opinión de la IA revisora (Avisá IA F1). Solo ops; null mientras la IA todavía no revisó. */
  public record OpsAi(
      String verdict,
      Double confidence,
      List<String> reasons,
      String suggestedCategory,
      String summary,
      OffsetDateTime reviewedAt
  ) {}

  /** Coincidencia IA vs. decisión humana (modo sombra). */
  public record AiAgreement(
      long reviewed,
      long decided,
      long agree,
      long aiApproveHumanRejected,
      long aiDoubtHumanApproved,
      long aiApproveHumanApproved,
      long aiDoubtHumanRejected,
      long pendingNoDecision
  ) {}

  public record OpsClosure(
      Long id,
      Long reportId,
      String photoUrl,
      String comment,
      OffsetDateTime createdAt,
      OpsReport report
  ) {}

  public record OpsPending(List<OpsReport> reports, List<OpsClosure> closures) {}

  public record ApproveResponse(String status, Long mergedIntoId) {}

  public record RejectRequest(String reason) {}

  public record PatchRequest(String category, Double lat, Double lng) {}

  public record ClosureReviewResponse(String status, Long reportId) {}
}
