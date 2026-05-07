package com.fixy.backend.dto;

import com.fixy.backend.model.LeadMessage;
import java.time.OffsetDateTime;

public record LeadMessageResponse(
    Long id,
    Long leadId,
    String sender,
    String text,
    OffsetDateTime createdAt
) {
  public static LeadMessageResponse fromEntity(LeadMessage message) {
    return new LeadMessageResponse(
        message.getId(),
        message.getLeadId(),
        message.getSender(),
        message.getText(),
        message.getCreatedAt()
    );
  }
}
