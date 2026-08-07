package com.fixy.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Mensaje en la conversación pública de un lead. Cada lead tiene su
 * propia "sala". El cliente accede usando lead.id + accessToken;
 * proveedor y operadores acceden vía endpoints autenticados.
 */
@Entity
@Table(name = "lead_messages", indexes = {
    @Index(name = "ix_lead_messages_lead_id_created", columnList = "leadId,createdAt")
})
public class LeadMessage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long leadId;

  /** "customer" | "provider" | "fixy" */
  @Column(nullable = false, length = 16)
  private String sender;

  @Column(nullable = false, length = 2000)
  private String text;

  /**
   * "all" (default) | "provider_only" | "customer_only". Controla quién ve
   * el mensaje en su chat: el cliente nunca debe ver info financiera del
   * proveedor (ej. su comisión), y ciertos avisos internos del proveedor no
   * le interesan al cliente. Sin audiencia especificada, se comporta como
   * siempre se comportó: visible para ambos.
   */
  @Column(nullable = false, length = 16)
  private String audience = "all";

  /**
   * URL relativa del audio de una nota de voz del cliente (ej.
   * "/uploads/lead-12/ab34cd.webm"), o null para mensajes de texto normales.
   * Cuando está presente, `text` es la TRANSCRIPCIÓN de ese audio: el agente
   * y el proveedor leen el texto, y el audio original queda disponible para
   * escucharlo si la transcripción le erró a algo.
   */
  @Column(length = 300)
  private String audioUrl;

  @Column(nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = OffsetDateTime.now();
    }
    if (audience == null) {
      audience = "all";
    }
  }

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getLeadId() { return leadId; }
  public void setLeadId(Long leadId) { this.leadId = leadId; }
  public String getSender() { return sender; }
  public void setSender(String sender) { this.sender = sender; }
  public String getText() { return text; }
  public void setText(String text) { this.text = text; }
  public String getAudience() { return audience; }
  public void setAudience(String audience) { this.audience = audience; }
  public String getAudioUrl() { return audioUrl; }
  public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
