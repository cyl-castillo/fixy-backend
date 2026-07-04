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
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Comisión de Fixy sobre un lead marcado COMPLETED por el proveedor
 * (P0-1). Entidad con ciclo de vida propio (no un campo más de Lead) para
 * mantener limpia la auditoría financiera.
 *
 * IMPORTANTE: todos los montos son {@link BigDecimal}, nunca Double. El
 * roadmap original (FIXY_ROADMAP.md, P0-1) sugería Double para
 * amountCharged/commissionAmount; se corrigió acá a propósito — Double usa
 * punto flotante binario, que no puede representar exactamente la mayoría
 * de los valores decimales de dinero (ej. 0.1 + 0.2 != 0.3) y acumula error
 * en sumas/redondeos. BigDecimal con escala fija (2 decimales, HALF_EVEN)
 * es el estándar para montos monetarios.
 *
 * commissionRate se persiste por registro (no se lee siempre de config):
 * si mañana Carlos cambia el % de comisión, los LeadPayment ya creados
 * conservan la tasa vigente al momento de completarse el trabajo.
 */
@Entity
@Table(
    name = "lead_payments",
    indexes = {
        @Index(name = "ix_lead_payments_commission_status", columnList = "commission_status"),
        @Index(name = "ix_lead_payments_provider_id", columnList = "provider_id")
    }
)
public class LeadPayment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "lead_id", nullable = false)
  private Long leadId;

  @Column(name = "provider_id", nullable = false)
  private Long providerId;

  @Column(name = "amount_charged", nullable = false, precision = 12, scale = 2)
  private BigDecimal amountCharged;

  @Column(name = "commission_rate", nullable = false, precision = 5, scale = 4)
  private BigDecimal commissionRate;

  @Column(name = "commission_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal commissionAmount;

  @Column(nullable = false, length = 8)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "commission_status", nullable = false, length = 20)
  private CommissionStatus commissionStatus;

  @Column(name = "mp_preference_id", length = 100)
  private String mpPreferenceId;

  @Column(name = "mp_payment_id", length = 100)
  private String mpPaymentId;

  @Column(name = "mp_payment_link", length = 500)
  private String mpPaymentLink;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "paid_at")
  private OffsetDateTime paidAt;

  @PrePersist
  void prePersist() {
    createdAt = OffsetDateTime.now();
    if (currency == null || currency.isBlank()) {
      currency = "UYU";
    }
    if (commissionStatus == null) {
      commissionStatus = CommissionStatus.PENDING;
    }
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getLeadId() { return leadId; }
  public void setLeadId(Long leadId) { this.leadId = leadId; }
  public Long getProviderId() { return providerId; }
  public void setProviderId(Long providerId) { this.providerId = providerId; }
  public BigDecimal getAmountCharged() { return amountCharged; }
  public void setAmountCharged(BigDecimal amountCharged) { this.amountCharged = amountCharged; }
  public BigDecimal getCommissionRate() { return commissionRate; }
  public void setCommissionRate(BigDecimal commissionRate) { this.commissionRate = commissionRate; }
  public BigDecimal getCommissionAmount() { return commissionAmount; }
  public void setCommissionAmount(BigDecimal commissionAmount) { this.commissionAmount = commissionAmount; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public CommissionStatus getCommissionStatus() { return commissionStatus; }
  public void setCommissionStatus(CommissionStatus commissionStatus) { this.commissionStatus = commissionStatus; }
  public String getMpPreferenceId() { return mpPreferenceId; }
  public void setMpPreferenceId(String mpPreferenceId) { this.mpPreferenceId = mpPreferenceId; }
  public String getMpPaymentId() { return mpPaymentId; }
  public void setMpPaymentId(String mpPaymentId) { this.mpPaymentId = mpPaymentId; }
  public String getMpPaymentLink() { return mpPaymentLink; }
  public void setMpPaymentLink(String mpPaymentLink) { this.mpPaymentLink = mpPaymentLink; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getPaidAt() { return paidAt; }
  public void setPaidAt(OffsetDateTime paidAt) { this.paidAt = paidAt; }
}
