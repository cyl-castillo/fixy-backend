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

@Entity
@Table(name = "providers")
public class Provider {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String phone;

  private String whatsappNumber;
  private String sourceName;

  @Column(nullable = false)
  private String sourceType;

  private String primaryZone;

  @Column(length = 1000)
  private String coverageZones;

  /** Descripción corta editable por el proveedor desde su panel
   * (self-service, Ola 2). Distinta de {@link #categoryNotes}, que es
   * interna de ops. Null hasta que el proveedor la cargue. */
  @Column(length = 500)
  private String description;

  private String city;
  private String department;

  @Column(nullable = false)
  private String categories;

  @Column(length = 2000)
  private String categoryNotes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ProviderStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ProviderVerificationStatus verificationStatus;

  private Double ratingAverage;
  private Integer ratingCount;
  private Integer internalScore;

  /** Disponibilidad MVP: false = "en pausa", no recibe nuevas oportunidades
   * en su bandeja ({@link com.fixy.backend.service.ProviderOpportunityService}).
   * Default true (disponible) para no romper a proveedores existentes. */
  @Column(nullable = false)
  private Boolean acceptingWork;

  /** Google Sign-In del proveedor (login-google): sub estable de la cuenta vinculada, null si nunca vinculó. */
  @Column(unique = true)
  private String googleSub;

  /** Email de la cuenta Google vinculada — solo para mostrar en el panel ("entrás con ..."). */
  private String googleEmail;

  @Column(length = 1000)
  private String riskFlags;

  private OffsetDateTime lastContactedAt;
  private OffsetDateTime lastRespondedAt;
  private Integer acceptedJobsCount;
  private Integer rejectedJobsCount;
  private Integer completedJobsCount;

  @Column(length = 4000)
  private String notes;

  /** Token aleatorio para acceso al panel del proveedor sin login.
   * Generado por ops vía /api/providers/{id}/access-token. */
  @Column(length = 64)
  private String accessToken;

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
      status = ProviderStatus.NEW;
    }
    if (verificationStatus == null) {
      verificationStatus = ProviderVerificationStatus.UNVERIFIED;
    }
    if (sourceType == null || sourceType.isBlank()) {
      sourceType = "manual";
    }
    if (acceptedJobsCount == null) {
      acceptedJobsCount = 0;
    }
    if (rejectedJobsCount == null) {
      rejectedJobsCount = 0;
    }
    if (completedJobsCount == null) {
      completedJobsCount = 0;
    }
    if (ratingCount == null) {
      ratingCount = 0;
    }
    if (internalScore == null) {
      internalScore = 0;
    }
    if (acceptingWork == null) {
      acceptingWork = true;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getPhone() { return phone; }
  public void setPhone(String phone) { this.phone = phone; }
  public String getWhatsappNumber() { return whatsappNumber; }
  public void setWhatsappNumber(String whatsappNumber) { this.whatsappNumber = whatsappNumber; }
  public String getSourceName() { return sourceName; }
  public void setSourceName(String sourceName) { this.sourceName = sourceName; }
  public String getSourceType() { return sourceType; }
  public void setSourceType(String sourceType) { this.sourceType = sourceType; }
  public String getPrimaryZone() { return primaryZone; }
  public void setPrimaryZone(String primaryZone) { this.primaryZone = primaryZone; }
  public String getCoverageZones() { return coverageZones; }
  public void setCoverageZones(String coverageZones) { this.coverageZones = coverageZones; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getCity() { return city; }
  public void setCity(String city) { this.city = city; }
  public String getDepartment() { return department; }
  public void setDepartment(String department) { this.department = department; }
  public String getCategories() { return categories; }
  public void setCategories(String categories) { this.categories = categories; }
  public String getCategoryNotes() { return categoryNotes; }
  public void setCategoryNotes(String categoryNotes) { this.categoryNotes = categoryNotes; }
  public ProviderStatus getStatus() { return status; }
  public void setStatus(ProviderStatus status) { this.status = status; }
  public ProviderVerificationStatus getVerificationStatus() { return verificationStatus; }
  public void setVerificationStatus(ProviderVerificationStatus verificationStatus) { this.verificationStatus = verificationStatus; }
  public Double getRatingAverage() { return ratingAverage; }
  public void setRatingAverage(Double ratingAverage) { this.ratingAverage = ratingAverage; }
  public Integer getRatingCount() { return ratingCount; }
  public void setRatingCount(Integer ratingCount) { this.ratingCount = ratingCount; }
  public Integer getInternalScore() { return internalScore; }
  public void setInternalScore(Integer internalScore) { this.internalScore = internalScore; }
  public String getGoogleSub() { return googleSub; }
  public void setGoogleSub(String googleSub) { this.googleSub = googleSub; }
  public String getGoogleEmail() { return googleEmail; }
  public void setGoogleEmail(String googleEmail) { this.googleEmail = googleEmail; }
  public Boolean getAcceptingWork() { return acceptingWork; }
  public void setAcceptingWork(Boolean acceptingWork) { this.acceptingWork = acceptingWork; }
  public String getRiskFlags() { return riskFlags; }
  public void setRiskFlags(String riskFlags) { this.riskFlags = riskFlags; }
  public OffsetDateTime getLastContactedAt() { return lastContactedAt; }
  public void setLastContactedAt(OffsetDateTime lastContactedAt) { this.lastContactedAt = lastContactedAt; }
  public OffsetDateTime getLastRespondedAt() { return lastRespondedAt; }
  public void setLastRespondedAt(OffsetDateTime lastRespondedAt) { this.lastRespondedAt = lastRespondedAt; }
  public Integer getAcceptedJobsCount() { return acceptedJobsCount; }
  public void setAcceptedJobsCount(Integer acceptedJobsCount) { this.acceptedJobsCount = acceptedJobsCount; }
  public Integer getRejectedJobsCount() { return rejectedJobsCount; }
  public void setRejectedJobsCount(Integer rejectedJobsCount) { this.rejectedJobsCount = rejectedJobsCount; }
  public Integer getCompletedJobsCount() { return completedJobsCount; }
  public void setCompletedJobsCount(Integer completedJobsCount) { this.completedJobsCount = completedJobsCount; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public String getAccessToken() { return accessToken; }
  public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
