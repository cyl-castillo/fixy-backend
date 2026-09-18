package com.fixy.backend.dto;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import java.time.OffsetDateTime;

public record ProviderAssignedLeadSummary(
    Long id,
    String customerName,
    String customerPhone,
    String detectedCategory,
    String urgency,
    String location,
    String summary,
    String problem,
    String address,
    String details,
    LeadStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String accessToken,
    /** Fase 2 (contrato B.5): el técnico tiene que saber que el dueño no está. */
    Boolean remote,
    OnSiteContact onSiteContact,
    /** Tier 1 (contrato §B.1): mismo campo que {@link com.fixy.backend.dto.LeadResponse#priceChange()},
     * para que el panel del proveedor vea el estado de su propia propuesta. */
    PriceChange priceChange,
    /** Tier 2 (contrato §B.2): franja corta ("hoy de 14 a 18"), null si no hay. */
    String arrivalWindow,
    /** Tier 2 (contrato §C.3): la reseña de este trabajo (para que el panel
     * la muestre y el proveedor pueda responder), null si todavía no la dejó. */
    Rating rating
) {
  public record OnSiteContact(String name, String phone) {}

  /** Tier 1 (contrato §B.1) — misma forma que {@link com.fixy.backend.dto.LeadResponse.PriceChange}. */
  public record PriceChange(
      java.math.BigDecimal proposedAmount,
      String reason,
      java.time.OffsetDateTime proposedAt,
      java.math.BigDecimal agreedAmount,
      java.time.OffsetDateTime agreedAt,
      com.fixy.backend.model.PriceChangeStatus status
  ) {
  }

  /** Tier 2 (contrato §C.3) — misma forma que {@link com.fixy.backend.dto.LeadResponse.Rating}. */
  public record Rating(
      Integer score,
      String comment,
      boolean verified,
      java.time.OffsetDateTime createdAt,
      String providerReply,
      java.time.OffsetDateTime providerReplyAt
  ) {
  }

  public static ProviderAssignedLeadSummary fromEntity(Lead lead) {
    return fromEntity(lead, null);
  }

  public static ProviderAssignedLeadSummary fromEntity(Lead lead, com.fixy.backend.model.LeadRating leadRating) {
    boolean remote = lead.isRemote();
    OnSiteContact onSite = remote && (lead.getOnSiteContactName() != null || lead.getOnSiteContactPhone() != null)
        ? new OnSiteContact(lead.getOnSiteContactName(), lead.getOnSiteContactPhone())
        : null;
    PriceChange priceChange = lead.getProposedAt() == null ? null : new PriceChange(
        lead.getProposedAmount(),
        lead.getProposedReason(),
        lead.getProposedAt(),
        lead.getAgreedAmount(),
        lead.getAgreedAt(),
        com.fixy.backend.model.PriceChangeStatus.of(lead)
    );
    Rating rating = leadRating == null ? null : new Rating(
        leadRating.getScore(),
        leadRating.getComment(),
        leadRating.isVerified(),
        leadRating.getCreatedAt(),
        leadRating.getProviderReply(),
        leadRating.getProviderReplyAt()
    );
    return new ProviderAssignedLeadSummary(
        lead.getId(),
        lead.getName(),
        lead.getPhone(),
        lead.getDetectedCategory(),
        lead.getUrgency(),
        lead.getLocation(),
        lead.getSummary(),
        lead.getProblem(),
        null,
        null,
        lead.getStatus(),
        lead.getCreatedAt(),
        lead.getUpdatedAt(),
        lead.getAccessToken(),
        remote,
        onSite,
        priceChange,
        lead.getArrivalWindow(),
        rating
    );
  }
}
