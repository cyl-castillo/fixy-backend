package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Ítem del catálogo de servicios con precio cerrado (Refundación de Fixy,
 * fase 1, contrato REFUNDACION_FASE1_CONTRATO.md §2, V28). {@code code} es
 * el slug estable que usa el pedido estructurado ({@code
 * POST /api/public/orders}) y el catálogo público; {@code category} es el
 * id de {@link ServiceCategory}. Soft delete vía {@link #active} (nunca
 * DELETE — mismo criterio que {@link BusinessCatalogItem}).
 */
@Entity
@Table(name = "service_catalog")
public class ServiceCatalogItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String category;

  @Column(nullable = false, length = 64, unique = true)
  private String code;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(length = 300)
  private String description;

  @Column(nullable = false)
  private Integer priceFrom;

  private Integer priceTo;

  private Integer durationMin;

  @Column(nullable = false)
  private boolean active = true;

  @Column(nullable = false)
  private int sortOrder;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public Integer getPriceFrom() { return priceFrom; }
  public void setPriceFrom(Integer priceFrom) { this.priceFrom = priceFrom; }
  public Integer getPriceTo() { return priceTo; }
  public void setPriceTo(Integer priceTo) { this.priceTo = priceTo; }
  public Integer getDurationMin() { return durationMin; }
  public void setDurationMin(Integer durationMin) { this.durationMin = durationMin; }
  public boolean isActive() { return active; }
  public void setActive(boolean active) { this.active = active; }
  public int getSortOrder() { return sortOrder; }
  public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
