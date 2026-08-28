package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Consulta de un vecino contra el catálogo estructurado de la ficha (Fase 2,
 * V25, gap analysis 2026-08-25 §2 "motor de respuesta con escalado al
 * dueño") — semántica distinta de {@link OfferInquiry}: acá el motor
 * ({@code CatalogAnswerService}) intenta responder solo antes de molestar a
 * nadie; {@link OfferInquiry} siempre es un mensaje de contacto humano.
 *
 * <p>{@link #businessId} SIN FK a propósito, mismo criterio que {@link
 * OfferInquiry#getBusinessId()}. {@link #accessToken} es la credencial del
 * vecino para volver a ver su respuesta sin login ({@code GET
 * /api/public/inquiries/{id}?token=}), generado con {@link
 * java.security.SecureRandom} igual que {@code Business.panelToken}.
 */
@Entity
@Table(name = "business_inquiries")
public class BusinessInquiry {

  public static final String ANSWER_SI = "SI";
  public static final String ANSWER_NO = "NO";
  public static final String SOURCE_CATALOG = "CATALOG";
  public static final String SOURCE_OWNER = "OWNER";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long businessId;

  private Long offerId;

  @Column(nullable = false, length = 500)
  private String question;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private BusinessInquiryStatus status;

  /** {@link #ANSWER_SI} | {@link #ANSWER_NO}. */
  @Column(length = 10)
  private String answer;

  @Column(length = 300)
  private String answerNote;

  /** {@link #SOURCE_CATALOG} | {@link #SOURCE_OWNER}. */
  @Column(length = 20)
  private String answerSource;

  /** Ítem del catálogo que resolvió (motor) o que se upserteó (dueño) esta consulta. */
  private Long catalogItemId;

  @Column(length = 80)
  private String visitorName;

  @Column(length = 30)
  private String visitorWhatsapp;

  @Column(nullable = false, unique = true, length = 64)
  private String accessToken;

  /** Endpoint de {@code PushSubscription} del vecino (Fase Push-2) para avisarle
   * cuando el dueño conteste — solo el puntero, las claves viven en push_subscriptions. */
  @Column(length = 500)
  private String pushEndpoint;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  private OffsetDateTime answeredAt;

  private OffsetDateTime ownerNotifiedAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getBusinessId() { return businessId; }
  public void setBusinessId(Long businessId) { this.businessId = businessId; }
  public Long getOfferId() { return offerId; }
  public void setOfferId(Long offerId) { this.offerId = offerId; }
  public String getQuestion() { return question; }
  public void setQuestion(String question) { this.question = question; }
  public BusinessInquiryStatus getStatus() { return status; }
  public void setStatus(BusinessInquiryStatus status) { this.status = status; }
  public String getAnswer() { return answer; }
  public void setAnswer(String answer) { this.answer = answer; }
  public String getAnswerNote() { return answerNote; }
  public void setAnswerNote(String answerNote) { this.answerNote = answerNote; }
  public String getAnswerSource() { return answerSource; }
  public void setAnswerSource(String answerSource) { this.answerSource = answerSource; }
  public Long getCatalogItemId() { return catalogItemId; }
  public void setCatalogItemId(Long catalogItemId) { this.catalogItemId = catalogItemId; }
  public String getVisitorName() { return visitorName; }
  public void setVisitorName(String visitorName) { this.visitorName = visitorName; }
  public String getVisitorWhatsapp() { return visitorWhatsapp; }
  public void setVisitorWhatsapp(String visitorWhatsapp) { this.visitorWhatsapp = visitorWhatsapp; }
  public String getAccessToken() { return accessToken; }
  public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
  public String getPushEndpoint() { return pushEndpoint; }
  public void setPushEndpoint(String pushEndpoint) { this.pushEndpoint = pushEndpoint; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getAnsweredAt() { return answeredAt; }
  public void setAnsweredAt(OffsetDateTime answeredAt) { this.answeredAt = answeredAt; }
  public OffsetDateTime getOwnerNotifiedAt() { return ownerNotifiedAt; }
  public void setOwnerNotifiedAt(OffsetDateTime ownerNotifiedAt) { this.ownerNotifiedAt = ownerNotifiedAt; }
}
