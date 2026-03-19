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
@Table(name = "leads")
public class Lead {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 2000)
  private String problem;

  private String name;
  private String phone;
  private String channel;
  private String detectedCategory;
  private String urgency;
  private String location;
  private String assignedProvider;

  @Column(length = 4000)
  private String notes;

  @Column(length = 12000)
  private String history;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private LeadStatus status;

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
      status = LeadStatus.NEW;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getProblem() { return problem; }
  public void setProblem(String problem) { this.problem = problem; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getPhone() { return phone; }
  public void setPhone(String phone) { this.phone = phone; }
  public String getChannel() { return channel; }
  public void setChannel(String channel) { this.channel = channel; }
  public String getDetectedCategory() { return detectedCategory; }
  public void setDetectedCategory(String detectedCategory) { this.detectedCategory = detectedCategory; }
  public String getUrgency() { return urgency; }
  public void setUrgency(String urgency) { this.urgency = urgency; }
  public String getLocation() { return location; }
  public void setLocation(String location) { this.location = location; }
  public String getAssignedProvider() { return assignedProvider; }
  public void setAssignedProvider(String assignedProvider) { this.assignedProvider = assignedProvider; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public String getHistory() { return history; }
  public void setHistory(String history) { this.history = history; }
  public LeadStatus getStatus() { return status; }
  public void setStatus(LeadStatus status) { this.status = status; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
