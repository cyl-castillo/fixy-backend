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
    ServiceFee serviceFee,
    /** Tier 1 (contrato §B.1): propuesta de precio nuevo del proveedor
     * ("protocolo al llegar"), null si nunca se propuso ninguna en este
     * lead. */
    PriceChange priceChange,
    /** Tier 2 (contrato §B.3): hora límite hasta la que Fixy promete seguir
     * buscando técnico. Null si ya hay técnico asignado (ASSIGNED+) o si el
     * lead nunca quedó listo para matching. */
    OffsetDateTime searchDeadlineAt,
    /** Tier 2 (contrato §B.3): {@code SEARCHING}|{@code CONTACTED}|
     * {@code NO_PROVIDER}|null — ver {@link com.fixy.backend.service.LeadService}
     * para el cálculo exacto. */
    String matchingState,
    /** Tier 2 (contrato §C.3): la reseña propia de este lead, null si
     * todavía no la dejó. */
    Rating rating
) {
  /** Tier 2 (contrato §C.3): reseña propia del lead (distinta de {@link
   * ReviewSnippet}, que es la de OTROS leads del mismo proveedor). */
  public record Rating(
      Integer score,
      String comment,
      boolean verified,
      OffsetDateTime createdAt,
      String providerReply,
      OffsetDateTime providerReplyAt
  ) {
  }
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
      java.util.List<ReviewSnippet> recentReviews,
      /** Tier 2 (contrato §B.1): foto del proveedor, null si no subió ninguna. */
      String photoUrl,
      /** Tier 2 (contrato §B.2): franja corta ("hoy de 14 a 18"), null si no hay. */
      String arrivalWindow
  ) {
  }

  /** Reseña pública anónima: solo estrella y texto (mejoras UX 2026-08). */
  public record ReviewSnippet(
      Integer score,
      String comment,
      /** Tier 2 (contrato §C.3): sello "reseña verificada" — el cargo de
       * servicio de ese lead estaba pagado al momento de calificar. */
      boolean verified,
      /** Tier 2 (contrato §C.3): respuesta pública del proveedor, null si no respondió. */
      String providerReply
  ) {
  }

  /** Tier 1 (contrato §B.1): estado público de la propuesta de precio
   * nuevo — ver {@link com.fixy.backend.model.PriceChangeStatus}. */
  public record PriceChange(
      java.math.BigDecimal proposedAmount,
      String reason,
      OffsetDateTime proposedAt,
      java.math.BigDecimal agreedAmount,
      OffsetDateTime agreedAt,
      com.fixy.backend.model.PriceChangeStatus status
  ) {
  }
}
