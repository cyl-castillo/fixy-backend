package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "lead_events")
public class LeadEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "lead_id", nullable = false)
  private Lead lead;

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
  public Lead getLead() { return lead; }
  public void setLead(Lead lead) { this.lead = lead; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getActor() { return actor; }
  public void setActor(String actor) { this.actor = actor; }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
