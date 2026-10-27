package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Reclamo vecinal público de Fixy Barrio (B1, BARRIO_B1_CONTRATO.md). Tabla propia, NO
 * {@code leads}: los schedulers de leads mandarían mensajes de técnico a estos vecinos.
 * {@code description} y {@code reporterPhone} son PRIVADOS (solo ops).
 */
@Entity
@Table(name = "barrio_reports", indexes = {
    @Index(name = "ix_barrio_reports_status_created", columnList = "status, created_at"),
    @Index(name = "ix_barrio_reports_category_status", columnList = "category, status")
})
public class BarrioReport {

  public static final String PENDING_REVIEW = "PENDING_REVIEW";
  public static final String PUBLISHED = "PUBLISHED";
  public static final String REJECTED = "REJECTED";
  public static final String MERGED = "MERGED";
  public static final String RESOLVED = "RESOLVED";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 16)
  private String category;

  @Column(nullable = false, length = 20)
  private String status = PENDING_REVIEW;

  @Column(nullable = false)
  private double lat;

  @Column(nullable = false)
  private double lng;

  @Column(nullable = false, length = 80)
  private String zone = "Ciudad de la Costa";

  @Column(name = "photo_path", nullable = false, length = 255)
  private String photoPath;

  @Column(length = 500)
  private String description;

  @Column(name = "reporter_phone", length = 30)
  private String reporterPhone;

  @Column(nullable = false, length = 12)
  private String channel = "web";

  @Column(nullable = false)
  private int supporters = 1;

  @Column(name = "merged_into_id")
  private Long mergedIntoId;

  @Column(name = "reject_reason", length = 300)
  private String rejectReason;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "published_at")
  private OffsetDateTime publishedAt;

  @Column(name = "resolved_at")
  private OffsetDateTime resolvedAt;

  @Column(name = "days_to_resolve")
  private Integer daysToResolve;

  @Column(name = "closure_photo_path", length = 255)
  private String closurePhotoPath;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public double getLat() { return lat; }
  public void setLat(double lat) { this.lat = lat; }
  public double getLng() { return lng; }
  public void setLng(double lng) { this.lng = lng; }
  public String getZone() { return zone; }
  public void setZone(String zone) { this.zone = zone; }
  public String getPhotoPath() { return photoPath; }
  public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getReporterPhone() { return reporterPhone; }
  public void setReporterPhone(String reporterPhone) { this.reporterPhone = reporterPhone; }
  public String getChannel() { return channel; }
  public void setChannel(String channel) { this.channel = channel; }
  public int getSupporters() { return supporters; }
  public void setSupporters(int supporters) { this.supporters = supporters; }
  public Long getMergedIntoId() { return mergedIntoId; }
  public void setMergedIntoId(Long mergedIntoId) { this.mergedIntoId = mergedIntoId; }
  public String getRejectReason() { return rejectReason; }
  public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getPublishedAt() { return publishedAt; }
  public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
  public OffsetDateTime getResolvedAt() { return resolvedAt; }
  public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
  public Integer getDaysToResolve() { return daysToResolve; }
  public void setDaysToResolve(Integer daysToResolve) { this.daysToResolve = daysToResolve; }
  public String getClosurePhotoPath() { return closurePhotoPath; }
  public void setClosurePhotoPath(String closurePhotoPath) { this.closurePhotoPath = closurePhotoPath; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
