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

  // --- Cierre verificable C1: pruebas. NUNCA se exponen en /api/public. ---

  @Column(name = "lat")
  private Double lat;

  @Column(name = "lng")
  private Double lng;

  @Column(name = "accuracy_m")
  private Double accuracyM;

  @Column(name = "distance_m")
  private Double distanceM;

  @Column(name = "photo_taken_at")
  private OffsetDateTime photoTakenAt;

  @Column(name = "photo_dhash")
  private Long photoDhash;

  @Column(name = "dhash_min_distance")
  private Integer dhashMinDistance;

  @Column(name = "ai_verdict", length = 12)
  private String aiVerdict;

  @Column(name = "ai_same_place")
  private Boolean aiSamePlace;

  @Column(name = "ai_issue_gone")
  private Boolean aiIssueGone;

  @Column(name = "ai_reused")
  private Boolean aiReused;

  @Column(name = "ai_confidence")
  private Double aiConfidence;

  @Column(name = "ai_summary", columnDefinition = "text")
  private String aiSummary;

  @Column(name = "ai_reasons", columnDefinition = "text")
  private String aiReasons;

  @Column(name = "ai_reviewed_at")
  private OffsetDateTime aiReviewedAt;

  @Column(name = "ai_attempts", nullable = false)
  private int aiAttempts = 0;

  @Column(name = "evidence_verdict", length = 12)
  private String evidenceVerdict;

  @Column(name = "evidence_reasons", columnDefinition = "text")
  private String evidenceReasons;

  @Column(name = "human_decision", length = 12)
  private String humanDecision;

  @Column(name = "human_decided_at")
  private OffsetDateTime humanDecidedAt;

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
  public Double getLat() { return lat; }
  public void setLat(Double lat) { this.lat = lat; }
  public Double getLng() { return lng; }
  public void setLng(Double lng) { this.lng = lng; }
  public Double getAccuracyM() { return accuracyM; }
  public void setAccuracyM(Double accuracyM) { this.accuracyM = accuracyM; }
  public Double getDistanceM() { return distanceM; }
  public void setDistanceM(Double distanceM) { this.distanceM = distanceM; }
  public OffsetDateTime getPhotoTakenAt() { return photoTakenAt; }
  public void setPhotoTakenAt(OffsetDateTime photoTakenAt) { this.photoTakenAt = photoTakenAt; }
  public Long getPhotoDhash() { return photoDhash; }
  public void setPhotoDhash(Long photoDhash) { this.photoDhash = photoDhash; }
  public Integer getDhashMinDistance() { return dhashMinDistance; }
  public void setDhashMinDistance(Integer dhashMinDistance) { this.dhashMinDistance = dhashMinDistance; }
  public String getAiVerdict() { return aiVerdict; }
  public void setAiVerdict(String aiVerdict) { this.aiVerdict = aiVerdict; }
  public Boolean getAiSamePlace() { return aiSamePlace; }
  public void setAiSamePlace(Boolean aiSamePlace) { this.aiSamePlace = aiSamePlace; }
  public Boolean getAiIssueGone() { return aiIssueGone; }
  public void setAiIssueGone(Boolean aiIssueGone) { this.aiIssueGone = aiIssueGone; }
  public Boolean getAiReused() { return aiReused; }
  public void setAiReused(Boolean aiReused) { this.aiReused = aiReused; }
  public Double getAiConfidence() { return aiConfidence; }
  public void setAiConfidence(Double aiConfidence) { this.aiConfidence = aiConfidence; }
  public String getAiSummary() { return aiSummary; }
  public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }
  public String getAiReasons() { return aiReasons; }
  public void setAiReasons(String aiReasons) { this.aiReasons = aiReasons; }
  public OffsetDateTime getAiReviewedAt() { return aiReviewedAt; }
  public void setAiReviewedAt(OffsetDateTime aiReviewedAt) { this.aiReviewedAt = aiReviewedAt; }
  public int getAiAttempts() { return aiAttempts; }
  public void setAiAttempts(int aiAttempts) { this.aiAttempts = aiAttempts; }
  public String getEvidenceVerdict() { return evidenceVerdict; }
  public void setEvidenceVerdict(String evidenceVerdict) { this.evidenceVerdict = evidenceVerdict; }
  public String getEvidenceReasons() { return evidenceReasons; }
  public void setEvidenceReasons(String evidenceReasons) { this.evidenceReasons = evidenceReasons; }
  public String getHumanDecision() { return humanDecision; }
  public void setHumanDecision(String humanDecision) { this.humanDecision = humanDecision; }
  public OffsetDateTime getHumanDecidedAt() { return humanDecidedAt; }
  public void setHumanDecidedAt(OffsetDateTime humanDecidedAt) { this.humanDecidedAt = humanDecidedAt; }
}
