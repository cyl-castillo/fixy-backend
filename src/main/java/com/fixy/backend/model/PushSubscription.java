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
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
