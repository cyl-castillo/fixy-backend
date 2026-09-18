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
import java.time.OffsetDateTime;

/**
 * Registro de una oferta uno-a-uno (Tier 2, contrato §A.1/§A.2): antes de
 * esto no existía una tabla (lead, proveedor, ofrecido, respondido) — la
 * latencia se reconstruía leyendo el timeline (PROVIDER_CONTACTED →
 * PROVIDER_STATUS_CHANGE/PROVIDER_DECLINED). Esta tabla es la fuente única
 * para el score de respuesta del ranking (§A.3) y las estadísticas del
 * proveedor ("Mis números").
 */
@Entity
@Table(name = "provider_offers", indexes = {
    @Index(name = "ix_provider_offers_provider_offered", columnList = "providerId,offeredAt"),
    @Index(name = "ix_provider_offers_lead", columnList = "leadId")
})
public class ProviderOffer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long leadId;

  @Column(nullable = false)
  private Long providerId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private ProviderOfferContext context;

  @Column(nullable = false)
  private OffsetDateTime offeredAt;

  /** false si esta oferta nació fuera del horario declarado por el
   * proveedor (fill rate manda: se ofrece igual, pero no cuenta como NO en
   * el score de respuesta ni dispara el aviso de "proveedor lento"). */
  @Column(nullable = false, columnDefinition = "boolean default true")
  private boolean inWindow = true;

  private OffsetDateTime respondedAt;

  @Enumerated(EnumType.STRING)
  @Column(length = 16)
  private ProviderOfferResponse response;

  @PrePersist
  void prePersist() {
    if (offeredAt == null) {
      offeredAt = OffsetDateTime.now();
    }
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getLeadId() { return leadId; }
  public void setLeadId(Long leadId) { this.leadId = leadId; }
  public Long getProviderId() { return providerId; }
  public void setProviderId(Long providerId) { this.providerId = providerId; }
  public ProviderOfferContext getContext() { return context; }
  public void setContext(ProviderOfferContext context) { this.context = context; }
  public OffsetDateTime getOfferedAt() { return offeredAt; }
  public void setOfferedAt(OffsetDateTime offeredAt) { this.offeredAt = offeredAt; }
  public boolean isInWindow() { return inWindow; }
  public void setInWindow(boolean inWindow) { this.inWindow = inWindow; }
  public OffsetDateTime getRespondedAt() { return respondedAt; }
  public void setRespondedAt(OffsetDateTime respondedAt) { this.respondedAt = respondedAt; }
  public ProviderOfferResponse getResponse() { return response; }
  public void setResponse(ProviderOfferResponse response) { this.response = response; }
}
