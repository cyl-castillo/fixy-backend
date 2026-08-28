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
 *
 * <p>Fase Push-2 (enganche): {@link #savedOfferIds} es el CSV de ids de
 * {@code Offer} que un suscriptor (cliente O visitante, nunca proveedor)
 * guardó desde la PWA — lo escribe {@code POST /api/public/push-subscriptions}
 * y lo lee/limpia {@code SavedOfferReminderScheduler} (ver
 * {@code SavedOfferIdsCodec}, la única pieza que sabe leer/escribir ese CSV).
 * {@link #lastSavedReminderAt} es el rate-limit propio de ese recordatorio
 * (máx 1 por día), independiente del digest semanal.
 *
 * <p>Fase 5 (panel self-service del comercio, V23): {@link #businessId} liga
 * la suscripción al comercio dueño — la setea {@code POST
 * /api/public/push-subscriptions} cuando el body trae un {@code
 * merchantToken} que resuelve a un {@code Business} (ver {@code
 * PushNotificationService#upsertPublicSubscription}). Campo independiente
 * de {@link #leadId}/{@link #providerId}: nunca los pisa. {@link
 * #lastMerchantReminderAt} es el throttle propio (máx 1 por día) del aviso
 * "tu oferta vence en 2 días" ({@code MerchantOfferExpiryScheduler}), mismo
 * patrón que {@link #lastSavedReminderAt}.
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

  @Column(length = 2000)
  private String savedOfferIds;

  private OffsetDateTime lastSavedReminderAt;

  @Column
  private Long businessId;

  private OffsetDateTime lastMerchantReminderAt;

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
  public String getSavedOfferIds() { return savedOfferIds; }
  public void setSavedOfferIds(String savedOfferIds) { this.savedOfferIds = savedOfferIds; }
  public OffsetDateTime getLastSavedReminderAt() { return lastSavedReminderAt; }
  public void setLastSavedReminderAt(OffsetDateTime lastSavedReminderAt) { this.lastSavedReminderAt = lastSavedReminderAt; }
  public Long getBusinessId() { return businessId; }
  public void setBusinessId(Long businessId) { this.businessId = businessId; }
  public OffsetDateTime getLastMerchantReminderAt() { return lastMerchantReminderAt; }
  public void setLastMerchantReminderAt(OffsetDateTime lastMerchantReminderAt) { this.lastMerchantReminderAt = lastMerchantReminderAt; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
