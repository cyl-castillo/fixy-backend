package com.fixy.backend.dto;

import com.fixy.backend.model.LeadMessage;
import java.time.OffsetDateTime;

public record LeadMessageResponse(
    Long id,
    Long leadId,
    String sender,
    String text,
    OffsetDateTime createdAt,
    String audience,
    String senderName,
    /** URL relativa del audio si el mensaje es una nota de voz (text = transcripción), null si es texto. */
    String audioUrl
) {
  public static LeadMessageResponse fromEntity(LeadMessage message) {
    return fromEntity(message, null);
  }

  /**
   * @param senderName nombre real a mostrar cuando sender=provider (ej. el
   *     nombre del proveedor asignado al lead). Null para fixy/customer o
   *     cuando no se resolvió (el frontend hace fallback a algo genérico).
   */
  public static LeadMessageResponse fromEntity(LeadMessage message, String senderName) {
    return new LeadMessageResponse(
        message.getId(),
        message.getLeadId(),
        message.getSender(),
        message.getText(),
        message.getCreatedAt(),
        message.getAudience(),
        "provider".equals(message.getSender()) ? senderName : null,
        message.getAudioUrl()
    );
  }
}
