package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

/**
 * Borrador de reclamo armado por WhatsApp: junta categoría + foto + ubicación en cualquier
 * orden, un borrador por teléfono (normalizado). Vence a las 2 h sin actividad.
 */
@Entity
@Table(name = "barrio_wa_drafts", uniqueConstraints = {
    @UniqueConstraint(name = "ux_barrio_wa_drafts_phone", columnNames = "phone")
})
public class BarrioWaDraft {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 30)
  private String phone;

  @Column(length = 16)
  private String category;

  @Column(name = "photo_path", length = 255)
  private String photoPath;

  private Double lat;

  private Double lng;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public Long getId() { return id; }
  public String getPhone() { return phone; }
  public void setPhone(String phone) { this.phone = phone; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getPhotoPath() { return photoPath; }
  public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }
  public Double getLat() { return lat; }
  public void setLat(Double lat) { this.lat = lat; }
  public Double getLng() { return lng; }
  public void setLng(Double lng) { this.lng = lng; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

  public boolean hasPhoto() { return photoPath != null && !photoPath.isBlank(); }
  public boolean hasLocation() { return lat != null && lng != null; }
}
