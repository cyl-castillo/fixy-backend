package com.fixy.backend.dto;

import com.fixy.backend.model.BusinessStatus;
import java.time.OffsetDateTime;

public record BusinessResponse(
    Long id,
    String name,
    String whatsappNumber,
    String category,
    String primaryZone,
    BusinessStatus status,
    Long providerId,
    String address,
    Double latitude,
    Double longitude,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    // Fase 5 (panel self-service del comercio): null hasta que ops pide el
    // link por primera vez (POST /api/businesses/{id}/panel-link) — ver
    // BusinessService.ensurePanelLink.
    String panelToken,
    // Fase 1 de la ficha (V24): descripción libre y multi-rubro CSV
    // (patrón Provider.categories) — aditivos sobre `category` (singular).
    String description,
    String categories
) {
}
