package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Ítem del catálogo estructurado de un {@link Business} (Fase 1 de la
 * mutación hacia ficha, V24): rubro/marca/producto con nivel de {@link
 * BusinessCatalogItemConfidence} — el dato que el motor de respuesta
 * consulta para "¿tenés X?". Soft delete vía {@link #active}, nunca hard
 * delete (ver {@code BusinessCatalogItemService.delete}).
 */
@Entity
@Table(name = "business_catalog_items")
public class BusinessCatalogItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "business_id", nullable = false)
  private Business business;

  @Column(nullable = false, length = 120)
  private String label;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private BusinessCatalogItemKind kind;

  private Integer priceFrom;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private BusinessCatalogItemConfidence confidence;

  /** Estampado por el server cuando {@link #confidence} pasa a {@code
   * CONFIRMADO} — nunca se borra si después deja de serlo, queda como
   * histórico de la última verificación (ver BusinessCatalogItemService). */
  private OffsetDateTime verifiedAt;

  @Column(length = 500)
  private String notes;

  /** Fase 2 (V25, motor de respuesta): true por default — un ítem DECLARADO
   * o CONFIRMADO con {@code available=false} es un "no tenemos X" real
   * (típicamente estampado por {@code BusinessInquiryService.answerAsOwner}
   * cuando el dueño contesta que no), no un ítem que simplemente no aplica.
   * {@link CatalogAnswerService} lo usa para decidir si el motor responde
   * "sí" o "no" cuando la confianza alcanza. */
  @Column(nullable = false)
  private boolean available = true;

  @Column(nullable = false)
  private boolean active = true;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Business getBusiness() { return business; }
  public void setBusiness(Business business) { this.business = business; }
  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
  public BusinessCatalogItemKind getKind() { return kind; }
  public void setKind(BusinessCatalogItemKind kind) { this.kind = kind; }
  public Integer getPriceFrom() { return priceFrom; }
  public void setPriceFrom(Integer priceFrom) { this.priceFrom = priceFrom; }
  public BusinessCatalogItemConfidence getConfidence() { return confidence; }
  public void setConfidence(BusinessCatalogItemConfidence confidence) { this.confidence = confidence; }
  public OffsetDateTime getVerifiedAt() { return verifiedAt; }
  public void setVerifiedAt(OffsetDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public boolean isAvailable() { return available; }
  public void setAvailable(boolean available) { this.available = available; }
  public boolean isActive() { return active; }
  public void setActive(boolean active) { this.active = active; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
