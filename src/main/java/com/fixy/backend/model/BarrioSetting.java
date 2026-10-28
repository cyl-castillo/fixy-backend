package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Ajuste de Avisá en tiempo real (clave/valor). Hoy solo {@code ai_auto_publish}. */
@Entity
@Table(name = "barrio_settings")
public class BarrioSetting {

  @Id
  // key y value van entrecomillados: son palabras reservadas en H2 (en Postgres las columnas son key/value, igual).
  @Column(name = "`key`", length = 64)
  private String key;

  @Column(name = "`value`", nullable = false, columnDefinition = "text")
  private String value;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  protected BarrioSetting() {
  }

  public BarrioSetting(String key, String value) {
    this.key = key;
    this.value = value;
    this.updatedAt = OffsetDateTime.now();
  }

  public String getKey() { return key; }
  public String getValue() { return value; }
  public void setValue(String value) { this.value = value; this.updatedAt = OffsetDateTime.now(); }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
