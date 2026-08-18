package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Consulta de un vecino a un comercio real vía la ruta "comercio" del CTA de
 * ofertas (FIXY_OFERTAS_CTA_DESIGN.md §4) — semántica distinta de
 * {@link Lead}: un mensaje de contacto completo desde el momento en que se
 * manda, no un pedido de servicio con datos faltantes. Cola propia,
 * separada de {@code /api/leads}, para no contaminar el embudo de demanda
 * real que Carlos ya usa.
 *
 * <p>Nace siempre en {@link #STATUS_NEW}; Carlos la marca
 * {@link #STATUS_FORWARDED} a mano cuando ya reenvió la consulta al
 * comercio por WhatsApp — dos estados alcanzan al volumen de Fase 1, sin un
 * ciclo de vida más rico (sin asignación, sin SLA, sin reapertura).
 */
@Entity
@Table(name = "offer_inquiries")
public class OfferInquiry {

  /** Recién creada, pendiente de que Carlos la reenvíe al comercio. */
  public static final String STATUS_NEW = "NEW";
  /** Carlos ya la reenvió al comercio a mano. */
  public static final String STATUS_FORWARDED = "FORWARDED";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long offerId;

  /** Denormalizado a propósito — evita join para el admin, mismo criterio que Offer.sourceName/sourceUrl. */
  @Column(nullable = false)
  private Long businessId;

  @Column(nullable = false, length = 100)
  private String name;

  /** Lo que tipeó el vecino — NO se valida contra Business.whatsappNumber. */
  @Column(nullable = false, length = 100)
  private String whatsappNumber;

  @Column(nullable = false, length = 500)
  private String message;

  /** "NEW" | "FORWARDED" — string plano, mismo patrón que Offer.origin. */
  @Column(nullable = false, length = 20)
  private String status;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now();
    createdAt = now;
    updatedAt = now;
    if (status == null || status.isBlank()) {
      status = STATUS_NEW;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getOfferId() { return offerId; }
  public void setOfferId(Long offerId) { this.offerId = offerId; }
  public Long getBusinessId() { return businessId; }
  public void setBusinessId(Long businessId) { this.businessId = businessId; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getWhatsappNumber() { return whatsappNumber; }
  public void setWhatsappNumber(String whatsappNumber) { this.whatsappNumber = whatsappNumber; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
