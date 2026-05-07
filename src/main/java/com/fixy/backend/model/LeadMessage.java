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

/**
 * Mensaje en la conversación pública de un lead. Cada lead tiene su
 * propia "sala". El cliente accede usando lead.id + accessToken;
 * proveedor y operadores acceden vía endpoints autenticados.
 */
@Entity
@Table(name = "lead_messages", indexes = {
    @Index(name = "ix_lead_messages_lead_id_created", columnList = "leadId,createdAt")
})
public class LeadMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long leadId;

  /** "customer" | "provider" | "fixy" */
  @Column(nullable = false, length = 16)
  private String sender;

  @Column(nullable = false, length = 2000)
  private String text;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getLeadId() { return leadId; }
  public void setLeadId(Long leadId) { this.leadId = leadId; }
  public String getSender() { return sender; }
  public void setSender(String sender) { this.sender = sender; }
  public String getText() { return text; }
  public void setText(String text) { this.text = text; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
