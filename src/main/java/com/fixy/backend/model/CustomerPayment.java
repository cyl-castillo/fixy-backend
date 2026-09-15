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
 * Cobro de Fixy al VECINO (Refundación de Fixy, fase 2, contrato
 * REFUNDACION_FASE2_CONTRATO.md §A.3, compartida con §B.2). Entidad propia
 * con ciclo de vida distinto del de {@link LeadPayment} (que es la comisión
 * al TÉCNICO, no al cliente) — mismo criterio de auditoría financiera
 * separada que ya usa LeadPayment.
 *
 * Dos usos según {@code kind}: {@link CustomerPaymentKind#SERVICE_FEE}
 * (cargo puntual sobre un lead completado — garantía 30 días + reseña
 * verificada) y {@link CustomerPaymentKind#PLAN_MONTHLY} (cuota del plan
 * Casa a distancia, sin lead).
 */
@Entity
@Table(
    name = "customer_payments",
    indexes = {
        @Index(name = "ix_customer_payments_status", columnList = "status"),
        @Index(name = "ix_customer_payments_lead_id", columnList = "lead_id"),
        @Index(name = "ix_customer_payments_remote_care_plan_id", columnList = "remote_care_plan_id")
    }
)
public class CustomerPayment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private CustomerPaymentKind kind;

  @Column(name = "lead_id")
  private Long leadId;

  @Column(name = "remote_care_plan_id")
  private Long remoteCarePlanId;

  @Column(name = "customer_name")
  private String customerName;

  @Column(name = "customer_phone")
  private String customerPhone;

  @Column(name = "base_amount", precision = 12, scale = 2)
  private BigDecimal baseAmount;

  @Column(name = "fee_percent", precision = 5, scale = 2)
  private BigDecimal feePercent;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 8)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private CustomerPaymentStatus status;

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

  @Column(name = "reminded_at")
  private OffsetDateTime remindedAt;

  @Column(name = "guarantee_until")
  private OffsetDateTime guaranteeUntil;

  @PrePersist
  void prePersist() {
    createdAt = OffsetDateTime.now();
    if (currency == null || currency.isBlank()) {
      currency = "UYU";
    }
    if (status == null) {
      status = CustomerPaymentStatus.PENDING;
    }
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public CustomerPaymentKind getKind() { return kind; }
  public void setKind(CustomerPaymentKind kind) { this.kind = kind; }
  public Long getLeadId() { return leadId; }
  public void setLeadId(Long leadId) { this.leadId = leadId; }
  public Long getRemoteCarePlanId() { return remoteCarePlanId; }
  public void setRemoteCarePlanId(Long remoteCarePlanId) { this.remoteCarePlanId = remoteCarePlanId; }
  public String getCustomerName() { return customerName; }
  public void setCustomerName(String customerName) { this.customerName = customerName; }
  public String getCustomerPhone() { return customerPhone; }
  public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
  public BigDecimal getBaseAmount() { return baseAmount; }
  public void setBaseAmount(BigDecimal baseAmount) { this.baseAmount = baseAmount; }
  public BigDecimal getFeePercent() { return feePercent; }
  public void setFeePercent(BigDecimal feePercent) { this.feePercent = feePercent; }
  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public CustomerPaymentStatus getStatus() { return status; }
  public void setStatus(CustomerPaymentStatus status) { this.status = status; }
  public String getMpPreferenceId() { return mpPreferenceId; }
  public void setMpPreferenceId(String mpPreferenceId) { this.mpPreferenceId = mpPreferenceId; }
  public String getMpPaymentId() { return mpPaymentId; }
  public void setMpPaymentId(String mpPaymentId) { this.mpPaymentId = mpPaymentId; }
  public String getMpPaymentLink() { return mpPaymentLink; }
  public void setMpPaymentLink(String mpPaymentLink) { this.mpPaymentLink = mpPaymentLink; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getPaidAt() { return paidAt; }
  public void setPaidAt(OffsetDateTime paidAt) { this.paidAt = paidAt; }
  public OffsetDateTime getRemindedAt() { return remindedAt; }
  public void setRemindedAt(OffsetDateTime remindedAt) { this.remindedAt = remindedAt; }
  public OffsetDateTime getGuaranteeUntil() { return guaranteeUntil; }
  public void setGuaranteeUntil(OffsetDateTime guaranteeUntil) { this.guaranteeUntil = guaranteeUntil; }
}
