package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

/**
 * Rating del cliente sobre un trabajo COMPLETED (P0-2 / H2.2). Entidad
 * propia (no un campo de LeadMessage) porque tiene ciclo de vida e
 * invariantes distintos: 1 rating por lead, alimenta agregados del
 * proveedor, y necesita su propio timestamp de auditoría.
 *
 * Constraint "1 rating por lead" se refuerza en dos capas: unique index en
 * DB (última palabra, sobrevive a bugs de concurrencia) y validación en
 * servicio (da un 400/409 con mensaje legible en vez de una excepción de
 * constraint SQL cruda).
 */
@Entity
@Table(
    name = "lead_ratings",
    uniqueConstraints = @UniqueConstraint(name = "uq_lead_ratings_lead_id", columnNames = "lead_id"),
    indexes = @Index(name = "ix_lead_ratings_provider_id", columnList = "provider_id")
)
public class LeadRating {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "lead_id", nullable = false)
  private Long leadId;

  @Column(name = "provider_id", nullable = false)
  private Long providerId;

  @Column(nullable = false)
  private Integer score;

  @Column(length = 1000)
  private String comment;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    createdAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getLeadId() { return leadId; }
  public void setLeadId(Long leadId) { this.leadId = leadId; }
  public Long getProviderId() { return providerId; }
  public void setProviderId(Long providerId) { this.providerId = providerId; }
  public Integer getScore() { return score; }
  public void setScore(Integer score) { this.score = score; }
  public String getComment() { return comment; }
  public void setComment(String comment) { this.comment = comment; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
