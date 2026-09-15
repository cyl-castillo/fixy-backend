package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

/**
 * Plan mensual "Casa a distancia" (Refundación de Fixy, fase 2, contrato
 * REFUNDACION_FASE2_CONTRATO.md §B.1/§B.2): para dueños que no viven en la
 * propiedad — un solo WhatsApp/página, prioridad de agenda, visita
 * preventiva por estación, fotos antes/después obligatorias, informe al
 * dueño.
 */
@Entity
@Table(
    name = "remote_care_plans",
    uniqueConstraints = @UniqueConstraint(name = "uk_remote_care_plans_access_token", columnNames = "access_token"),
    indexes = @Index(name = "ix_remote_care_plans_status", columnList = "status")
)
public class RemoteCarePlan {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "owner_name", nullable = false)
  private String ownerName;

  @Column(name = "owner_phone", nullable = false)
  private String ownerPhone;

  @Column(name = "owner_email")
  private String ownerEmail;

  /** Zona canónica (CoverageZone.label()), mismo criterio que Lead.location. */
  @Column(name = "property_zone", nullable = false)
  private String propertyZone;

  @Column(name = "property_address", nullable = false)
  private String propertyAddress;

  @Column(name = "on_site_name")
  private String onSiteName;

  @Column(name = "on_site_phone")
  private String onSitePhone;

  @Column(length = 1000)
  private String notes;

  @Column(name = "monthly_price", nullable = false)
  private int monthlyPrice;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private RemoteCarePlanStatus status;

  @Column(name = "access_token", nullable = false, length = 64)
  private String accessToken;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "activated_at")
  private OffsetDateTime activatedAt;

  @Column(name = "last_billed_at")
  private OffsetDateTime lastBilledAt;

  @Column(name = "next_visit_note", length = 500)
  private String nextVisitNote;

  @PrePersist
  void prePersist() {
    createdAt = OffsetDateTime.now();
    if (status == null) {
      status = RemoteCarePlanStatus.REQUESTED;
    }
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getOwnerName() { return ownerName; }
  public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
  public String getOwnerPhone() { return ownerPhone; }
  public void setOwnerPhone(String ownerPhone) { this.ownerPhone = ownerPhone; }
  public String getOwnerEmail() { return ownerEmail; }
  public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }
  public String getPropertyZone() { return propertyZone; }
  public void setPropertyZone(String propertyZone) { this.propertyZone = propertyZone; }
  public String getPropertyAddress() { return propertyAddress; }
  public void setPropertyAddress(String propertyAddress) { this.propertyAddress = propertyAddress; }
  public String getOnSiteName() { return onSiteName; }
  public void setOnSiteName(String onSiteName) { this.onSiteName = onSiteName; }
  public String getOnSitePhone() { return onSitePhone; }
  public void setOnSitePhone(String onSitePhone) { this.onSitePhone = onSitePhone; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public int getMonthlyPrice() { return monthlyPrice; }
  public void setMonthlyPrice(int monthlyPrice) { this.monthlyPrice = monthlyPrice; }
  public RemoteCarePlanStatus getStatus() { return status; }
  public void setStatus(RemoteCarePlanStatus status) { this.status = status; }
  public String getAccessToken() { return accessToken; }
  public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getActivatedAt() { return activatedAt; }
  public void setActivatedAt(OffsetDateTime activatedAt) { this.activatedAt = activatedAt; }
  public OffsetDateTime getLastBilledAt() { return lastBilledAt; }
  public void setLastBilledAt(OffsetDateTime lastBilledAt) { this.lastBilledAt = lastBilledAt; }
  public String getNextVisitNote() { return nextVisitNote; }
  public void setNextVisitNote(String nextVisitNote) { this.nextVisitNote = nextVisitNote; }
}
