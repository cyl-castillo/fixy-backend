package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Oferta de un {@link Business} (diseño FIXY_OFERTAS_INGESTA_DESIGN.md §3.2).
 * Nace siempre en {@code DRAFT} — ninguna oferta pasa a {@code ACTIVE} sin
 * una acción explícita de ops ({@code OfferService.approve}), sea cual sea
 * su {@link #origin}.
 */
@Entity
@Table(name = "offers")
public class Offer {

  /** Alta manual desde el panel admin. */
  public static final String ORIGIN_MANUAL = "manual";
  /** Reenvío por WhatsApp con extracción por IA (fuera de alcance de este commit). */
  public static final String ORIGIN_WHATSAPP_FORWARD = "whatsapp_forward";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long businessId;

  @Column(nullable = false)
  private String title;

  /** Reusa ServiceCategory, o "otro" si el rubro no está en el catálogo MVP. */
  @Column(nullable = false)
  private String category;

  /** Reusa CoverageZone. */
  private String zone;

  @Column(length = 1000)
  private String description;

  /** "20% off", "2x1", "$500 fijo" — texto libre, sin parseo numérico. */
  private String discountText;

  private OffsetDateTime validFrom;
  private OffsetDateTime validUntil;

  /** Mismo patrón de storage que LeadVoiceNoteService.store() (uploads/ + urlPrefix). */
  private String photoUrl;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OfferStatus status;

  /** "manual" | "whatsapp_forward" — constante liviana, mismo patrón que Provider.sourceType. */
  @Column(nullable = false)
  private String origin;

  /** Texto/caption original del mensaje de WhatsApp, para auditoría de la aprobación humana. */
  @Column(length = 2000)
  private String sourceMessageRaw;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now();
    createdAt = now;
    updatedAt = now;
    if (status == null) {
      status = OfferStatus.DRAFT;
    }
    if (origin == null || origin.isBlank()) {
      origin = ORIGIN_MANUAL;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getBusinessId() { return businessId; }
  public void setBusinessId(Long businessId) { this.businessId = businessId; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getZone() { return zone; }
  public void setZone(String zone) { this.zone = zone; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getDiscountText() { return discountText; }
  public void setDiscountText(String discountText) { this.discountText = discountText; }
  public OffsetDateTime getValidFrom() { return validFrom; }
  public void setValidFrom(OffsetDateTime validFrom) { this.validFrom = validFrom; }
  public OffsetDateTime getValidUntil() { return validUntil; }
  public void setValidUntil(OffsetDateTime validUntil) { this.validUntil = validUntil; }
  public String getPhotoUrl() { return photoUrl; }
  public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
  public OfferStatus getStatus() { return status; }
  public void setStatus(OfferStatus status) { this.status = status; }
  public String getOrigin() { return origin; }
  public void setOrigin(String origin) { this.origin = origin; }
  public String getSourceMessageRaw() { return sourceMessageRaw; }
  public void setSourceMessageRaw(String sourceMessageRaw) { this.sourceMessageRaw = sourceMessageRaw; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
