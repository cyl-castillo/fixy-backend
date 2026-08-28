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

  /** Rubro: reusa ServiceCategory.id si aplica, o texto libre "otro". Queda
   * por compatibilidad con lo que ya lee OfferService.ingest — {@link
   * #categories} es la generalización multi-rubro de la ficha (Fase 1,
   * V24), no un reemplazo. */
  @Column(nullable = false)
  private String category;

  /** Descripción libre de la ficha (Fase 1, V24) — nullable, sin backfill;
   * lo carga ops o el dueño desde el panel self-service. */
  @Column(length = 500)
  private String description;

  /** Multi-rubro CSV, mismo patrón que {@code Provider.categories} — NO
   * catálogo paralelo de rubros, texto libre separado por comas. {@link
   * #category} (singular) sigue siendo la fuente que usa el matching
   * existente; este campo es aditivo. */
  @Column(length = 500)
  private String categories;

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

  /** Identificador URL-safe de la ficha pública {@code /comercio/{slug}}
   * (Fase 3, V26) — lazy e idempotente, mismo criterio que {@link
   * #panelToken}: nunca se regenera solo una vez asignado (ver
   * {@code BusinessSlugService.ensureSlug}). Único cuando no es null. */
  @Column(unique = true, length = 80)
  private String slug;

  /** Contador de vistas de la ficha pública (Fase 3, V26): {@code GET
   * /api/public/businesses/{slug}} lo incrementa fire-and-forget (ver
   * {@code PublicBusinessService}) — a diferencia de {@code
   * Offer.viewCount} no hay un POST /view separado, el propio GET detalle
   * lo suma. columnDefinition con default: en dev/test el esquema lo
   * mantiene ddl-auto=update sobre una H2 persistente con filas previas —
   * sin el default el ALTER ADD COLUMN NOT NULL falla en silencio (ver
   * BusinessCatalogItem.available). */
  @Column(name = "view_count", nullable = false, columnDefinition = "bigint not null default 0")
  private long viewCount;

  /** Google Sign-In del dueño del comercio (Fase 1, V27) — mismo patrón que
   * {@code Provider.googleSub}: llave estable de la cuenta vinculada, único
   * cuando no es null. Re-vincular el MISMO comercio con OTRA cuenta está
   * permitido (la posesión del link del panel manda); solo se rechaza si el
   * sub ya está vinculado a OTRO comercio (ver {@code
   * BusinessGoogleAuthService.link}). */
  @Column(name = "google_sub", unique = true, length = 255)
  private String googleSub;

  /** Email de la cuenta de Google vinculada — dato aditivo, nullable hasta
   * que el dueño vincula. Primera vez que Fixy conoce el email real del
   * dueño del comercio (ver {@code BusinessGoogleAuthService}). */
  @Column(name = "google_email", length = 255)
  private String googleEmail;

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
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getCategories() { return categories; }
  public void setCategories(String categories) { this.categories = categories; }
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
  public String getSlug() { return slug; }
  public void setSlug(String slug) { this.slug = slug; }
  public long getViewCount() { return viewCount; }
  public void setViewCount(long viewCount) { this.viewCount = viewCount; }
  public String getGoogleSub() { return googleSub; }
  public void setGoogleSub(String googleSub) { this.googleSub = googleSub; }
  public String getGoogleEmail() { return googleEmail; }
  public void setGoogleEmail(String googleEmail) { this.googleEmail = googleEmail; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
