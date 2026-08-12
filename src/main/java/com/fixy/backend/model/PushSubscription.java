package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Suscripción de push del navegador (Web Push / VAPID), guardada tal cual
 * la entrega {@code PushManager.subscribe()} del cliente: endpoint del
 * push service del navegador + claves de cifrado p256dh/auth.
 *
 * Pertenece a un cliente (leadId) O a un proveedor (providerId), nunca a
 * ambos — mismo lead/provider puede tener varias filas (varios
 * dispositivos/navegadores suscriptos). Suscripciones muertas (el push
 * service devuelve 404/410) se borran solas al fallar el envío, ver
 * {@link com.fixy.backend.service.PushNotificationService}.
 *
 * <p>{@link #zone} (Fase Push-1, FIXY_OFERTAS_PUSH_Y_MAPA.md §3) es la zona
 * canónica ({@link com.fixy.backend.model.CoverageZone#label()}) del cliente
 * al momento del alta — resuelta desde {@code Lead.location}, null si el
 * lead no declaró una zona que Fixy reconozca (solo aplica a suscripciones
 * de cliente; las de proveedor no la usan). {@link #lastOffersDigestAt}
 * es el rate-limit del digest semanal de ofertas: null hasta el primer envío.
 */
@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column
  private Long leadId;

  @Column
  private Long providerId;

  @Column(nullable = false, length = 2000)
  private String endpoint;

  @Column(nullable = false, length = 200)
  private String p256dh;

  @Column(nullable = false, length = 200)
  private String auth;

  private String zone;

  private OffsetDateTime lastOffersDigestAt;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) createdAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getLeadId() { return leadId; }
  public void setLeadId(Long leadId) { this.leadId = leadId; }
  public Long getProviderId() { return providerId; }
  public void setProviderId(Long providerId) { this.providerId = providerId; }
  public String getEndpoint() { return endpoint; }
  public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
  public String getP256dh() { return p256dh; }
  public void setP256dh(String p256dh) { this.p256dh = p256dh; }
  public String getAuth() { return auth; }
  public void setAuth(String auth) { this.auth = auth; }
  public String getZone() { return zone; }
  public void setZone(String zone) { this.zone = zone; }
  public OffsetDateTime getLastOffersDigestAt() { return lastOffersDigestAt; }
  public void setLastOffersDigestAt(OffsetDateTime lastOffersDigestAt) { this.lastOffersDigestAt = lastOffersDigestAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
