package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Foto del trabajo subida por un proveedor a un lead. */
@Entity
@Table(name = "lead_photos", indexes = {
    @Index(name = "ix_lead_photos_lead_id_created", columnList = "leadId,createdAt")
})
public class LeadPhoto {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long leadId;

  /** Quién subió. Puede ser null si lo sube ops directo. */
  private Long providerId;

  /** Path relativo dentro de fixy.uploads.dir. Ej: "lead-12/abc123.jpg". */
  @Column(nullable = false, length = 500)
  private String relativePath;

  @Column(length = 200)
  private String originalName;

  @Column(length = 100)
  private String contentType;

  private Long sizeBytes;

  @Column(length = 500)
  private String caption;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) createdAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getLeadId() { return leadId; }
  public void setLeadId(Long leadId) { this.leadId = leadId; }
  public Long getProviderId() { return providerId; }
  public void setProviderId(Long providerId) { this.providerId = providerId; }
  public String getRelativePath() { return relativePath; }
  public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
  public String getOriginalName() { return originalName; }
  public void setOriginalName(String originalName) { this.originalName = originalName; }
  public String getContentType() { return contentType; }
  public void setContentType(String contentType) { this.contentType = contentType; }
  public Long getSizeBytes() { return sizeBytes; }
  public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
  public String getCaption() { return caption; }
  public void setCaption(String caption) { this.caption = caption; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
