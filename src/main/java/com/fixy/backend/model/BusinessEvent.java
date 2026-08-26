package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Timeline append-only de la ficha de un {@link Business} (Fase 1, V24) —
 * espejo exacto de {@link LeadEvent}: nunca se actualiza ni se borra.
 * {@code businessId} denormalizado SIN relación JPA a propósito, mismo
 * criterio que {@link OfferInquiry#getBusinessId()} — evita join para el
 * admin y no depende de integridad referencial estricta acá.
 */
@Entity
@Table(name = "business_events")
public class BusinessEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long businessId;

  @Column(nullable = false, length = 80)
  private String type;

  @Column(nullable = false, length = 80)
  private String actor;

  @Column(nullable = false, length = 4000)
  private String message;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    createdAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getBusinessId() { return businessId; }
  public void setBusinessId(Long businessId) { this.businessId = businessId; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getActor() { return actor; }
  public void setActor(String actor) { this.actor = actor; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
