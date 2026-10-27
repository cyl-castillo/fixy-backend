package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Reclamo de cierre ("ya lo arreglaron") con foto obligatoria; ops lo aprueba o rechaza. */
@Entity
@Table(name = "barrio_closure_claims", indexes = {
    @Index(name = "ix_barrio_closure_claims_status", columnList = "status, created_at"),
    @Index(name = "ix_barrio_closure_claims_report", columnList = "report_id")
})
public class BarrioClosureClaim {

  public static final String PENDING_REVIEW = "PENDING_REVIEW";
  public static final String APPROVED = "APPROVED";
  public static final String REJECTED = "REJECTED";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "report_id", nullable = false)
  private Long reportId;

  @Column(name = "photo_path", nullable = false, length = 255)
  private String photoPath;

  @Column(length = 500)
  private String comment;

  @Column(nullable = false, length = 20)
  private String status = PENDING_REVIEW;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "reviewed_at")
  private OffsetDateTime reviewedAt;

  public Long getId() { return id; }
  public Long getReportId() { return reportId; }
  public void setReportId(Long reportId) { this.reportId = reportId; }
  public String getPhotoPath() { return photoPath; }
  public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }
  public String getComment() { return comment; }
  public void setComment(String comment) { this.comment = comment; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getReviewedAt() { return reviewedAt; }
  public void setReviewedAt(OffsetDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
