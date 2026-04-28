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
@Table(name = "provider_leads")
public class ProviderLead {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 2000)
  private String message;

  private String name;
  private String phone;
  private String channel;
  private String category;
  private String zone;
  private String urgency;
  private String summary;

  @Column(length = 3000)
  private String missingFields;

  @Column(nullable = false)
  private boolean readyForReview;

  @Column(length = 4000)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ProviderLeadStatus status;

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
      status = ProviderLeadStatus.PENDING_REVIEW;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getPhone() { return phone; }
  public void setPhone(String phone) { this.phone = phone; }
  public String getChannel() { return channel; }
  public void setChannel(String channel) { this.channel = channel; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getZone() { return zone; }
  public void setZone(String zone) { this.zone = zone; }
  public String getUrgency() { return urgency; }
  public void setUrgency(String urgency) { this.urgency = urgency; }
  public String getSummary() { return summary; }
  public void setSummary(String summary) { this.summary = summary; }
  public String getMissingFields() { return missingFields; }
  public void setMissingFields(String missingFields) { this.missingFields = missingFields; }
  public boolean isReadyForReview() { return readyForReview; }
  public void setReadyForReview(boolean readyForReview) { this.readyForReview = readyForReview; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public ProviderLeadStatus getStatus() { return status; }
  public void setStatus(ProviderLeadStatus status) { this.status = status; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
