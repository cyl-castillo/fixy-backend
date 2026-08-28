package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Franja horaria de un {@link Business} (Fase 1 de la ficha, V24). Varias
 * filas por día son válidas a propósito (horario partido). {@code
 * dayOfWeek} es ISO (1=lunes .. 7=domingo); {@code opensAt}/{@code closesAt}
 * son texto {@code "HH:mm"} — alcanza para mostrar/comparar en hora local de
 * Uruguay, sin lidiar con zona horaria en un dato que nunca cruza uso.
 *
 * <p>Sin {@code updatedAt}: el set completo del comercio se reemplaza entero
 * en cada {@code PUT /api/businesses/{id}/hours} (ver
 * {@code BusinessHourService.replace}), no hay edición fila a fila que
 * justifique trackear cuándo cambió cada una.
 */
@Entity
@Table(name = "business_hours")
public class BusinessHour {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "business_id", nullable = false)
  private Business business;

  @Column(name = "day_of_week", nullable = false)
  private short dayOfWeek;

  @Column(nullable = false, length = 5)
  private String opensAt;

  @Column(nullable = false, length = 5)
  private String closesAt;

  @Column(length = 200)
  private String note;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    createdAt = OffsetDateTime.now();
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Business getBusiness() { return business; }
  public void setBusiness(Business business) { this.business = business; }
  public short getDayOfWeek() { return dayOfWeek; }
  public void setDayOfWeek(short dayOfWeek) { this.dayOfWeek = dayOfWeek; }
  public String getOpensAt() { return opensAt; }
  public void setOpensAt(String opensAt) { this.opensAt = opensAt; }
  public String getClosesAt() { return closesAt; }
  public void setClosesAt(String closesAt) { this.closesAt = closesAt; }
  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
}
