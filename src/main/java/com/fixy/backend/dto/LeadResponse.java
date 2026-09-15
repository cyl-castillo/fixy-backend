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
    AssignedProviderSummary assignedProviderSummary,
    /** Código del servicio de catálogo (contrato §3) si el lead vino de un
     * pedido estructurado, null para leads del chat conversacional. */
    String serviceCode,
    /** Nombre legible del servicio — null si {@code serviceCode} es null o
     * ya no existe/está activo en el catálogo. */
    String serviceName,
    /** Precio orientativo desde (UYU) del servicio pedido, null si no aplica. */
    Integer priceFrom,
    /** Ventana horaria elegida (id de {@code OrderTimeWindow}), null si no aplica. */
    String timeWindow,
    boolean remote,
    /** Quién abre la puerta si {@code remote=true}. Null si no aplica. */
    OnSiteContact onSiteContact,
    /** Refundación fase 2 (contrato §A.4.7): cargo de servicio al cliente de
     * este lead, null si el trabajo todavía no fue marcado COMPLETED (o si
     * el cargo redondeó a 0, ver CustomerPaymentService). */
    ServiceFee serviceFee
) {
  /** Contacto que abre la puerta cuando el cliente no va a estar (contrato §3). */
  public record OnSiteContact(String name, String phone) {
  }

  /** Refundación fase 2 (contrato §A.4.7): {@code
   * GET /api/public/leads/{id}?token=} incluye {@code serviceFee:
   * {amount, status, paymentLink, guaranteeUntil} | null}. */
  public record ServiceFee(
      java.math.BigDecimal amount,
      com.fixy.backend.model.CustomerPaymentStatus status,
      String paymentLink,
      OffsetDateTime guaranteeUntil
  ) {
  }

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
