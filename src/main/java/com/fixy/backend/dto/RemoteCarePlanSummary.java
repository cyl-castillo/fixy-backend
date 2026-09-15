package com.fixy.backend.dto;

import com.fixy.backend.model.RemoteCarePlan;
import com.fixy.backend.model.RemoteCarePlanStatus;
import java.time.OffsetDateTime;

/** Fila del panel ops "Casa a distancia" (contrato §B.5). */
public record RemoteCarePlanSummary(
    Long id,
    RemoteCarePlanStatus status,
    String ownerName,
    String ownerPhone,
    String zone,
    String address,
    int monthlyPrice,
    OffsetDateTime createdAt,
    OffsetDateTime activatedAt,
    OffsetDateTime lastBilledAt,
    String nextVisitNote,
    String accessToken
) {
  public static RemoteCarePlanSummary fromEntity(RemoteCarePlan plan) {
    return new RemoteCarePlanSummary(
        plan.getId(),
        plan.getStatus(),
        plan.getOwnerName(),
        plan.getOwnerPhone(),
        plan.getPropertyZone(),
        plan.getPropertyAddress(),
        plan.getMonthlyPrice(),
        plan.getCreatedAt(),
        plan.getActivatedAt(),
        plan.getLastBilledAt(),
        plan.getNextVisitNote(),
        plan.getAccessToken()
    );
  }
}
