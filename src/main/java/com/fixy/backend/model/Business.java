package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Comercio de ofertas (panadería, gimnasio, etc.) — semántica distinta de
 * {@link Provider}: puede no ofrecer ningún servicio del catálogo de
 * {@link ServiceCategory} que participa en matching, y su ciclo de
 * aprobación ("puede publicar una oferta") es independiente del de
 * proveedor ("puede recibir y trabajar un lead asignado"). Ver diseño
 * FIXY_OFERTAS_INGESTA_DESIGN.md §3.1.
 *
 * <p>Un comercio puede SER también un {@link Provider} real (mismo número
 * de WhatsApp, dos intenciones distintas) — {@link #providerId} es un
 * vínculo explícito para reporting, no una fusión de modelos.
 */
@Entity
@Table(name = "businesses")
public class Business {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String name;

  /** Lookup del comercio, análogo a Provider.phone/whatsappNumber. */
  @Column(nullable = false)
  private String whatsappNumber;

  /** Rubro: reusa ServiceCategory.id si aplica, o texto libre "otro". */
  @Column(nullable = false)
  private String category;

  /** Reusa CoverageZone (fromLabel) — NO catálogo paralelo de zonas. */
  private String primaryZone;

  /** Texto libre, nullable — dato barato hoy, mapa mañana (ver V17). */
  private String address;

  /** Pin en mapa, fase 3 (ver V21) — nullable: hoy solo lo llena el alta
   * pública cuando el navegador del comerciante da la geolocalización. */
  private Double latitude;
  private Double longitude;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private BusinessStatus status;

  /** FK opcional: si el mismo comercio también es Provider (ver javadoc de clase). */
  private Long providerId;

  /** Token del panel self-service del dueño (Fase 5, V23) — URL-safe,
   * generado lazy la primera vez que ops pide el link
   * ({@code BusinessService.ensurePanelLink}), único cuando no es null.
   * Nunca se regenera solo: reemplazarlo invalidaría el link que el
   * comerciante ya guardó. */
  @Column(unique = true, length = 64)
  private String panelToken;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now();
    createdAt = now;
    updatedAt = now;
    if (status == null) {
      status = BusinessStatus.ACTIVE;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getWhatsappNumber() { return whatsappNumber; }
  public void setWhatsappNumber(String whatsappNumber) { this.whatsappNumber = whatsappNumber; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getPrimaryZone() { return primaryZone; }
  public void setPrimaryZone(String primaryZone) { this.primaryZone = primaryZone; }
  public String getAddress() { return address; }
  public void setAddress(String address) { this.address = address; }
  public Double getLatitude() { return latitude; }
  public void setLatitude(Double latitude) { this.latitude = latitude; }
  public Double getLongitude() { return longitude; }
  public void setLongitude(Double longitude) { this.longitude = longitude; }
  public BusinessStatus getStatus() { return status; }
  public void setStatus(BusinessStatus status) { this.status = status; }
  public Long getProviderId() { return providerId; }
  public void setProviderId(Long providerId) { this.providerId = providerId; }
  public String getPanelToken() { return panelToken; }
  public void setPanelToken(String panelToken) { this.panelToken = panelToken; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
