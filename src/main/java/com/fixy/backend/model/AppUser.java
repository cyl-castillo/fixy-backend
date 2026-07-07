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
 * Usuario final (cliente) autenticado con Google Sign-In. Login opcional y
 * post-valor: el chat anónimo por accessToken sigue siendo el flujo
 * principal. Este registro existe para dar identidad persistente
 * cross-device y permitir vincular leads ya creados como anónimo.
 */
@Entity
@Table(name = "users")
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** "sub" del ID token de Google: identificador estable de la cuenta. */
  @Column(name = "google_sub", nullable = false, unique = true, length = 255)
  private String googleSub;

  @Column(length = 255)
  private String email;

  @Column(length = 255)
  private String name;

  @Column(name = "picture_url", length = 1000)
  private String pictureUrl;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "last_login_at", nullable = false)
  private OffsetDateTime lastLoginAt;

  @PrePersist
  void prePersist() {
    OffsetDateTime now = OffsetDateTime.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (lastLoginAt == null) {
      lastLoginAt = now;
    }
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getGoogleSub() { return googleSub; }
  public void setGoogleSub(String googleSub) { this.googleSub = googleSub; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getPictureUrl() { return pictureUrl; }
  public void setPictureUrl(String pictureUrl) { this.pictureUrl = pictureUrl; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
  public void setLastLoginAt(OffsetDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
