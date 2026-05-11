package com.fixy.backend.dto;

import com.fixy.backend.model.LeadPhoto;
import java.time.OffsetDateTime;

public record LeadPhotoResponse(
    Long id,
    Long leadId,
    Long providerId,
    String url,
    String originalName,
    String contentType,
    Long sizeBytes,
    String caption,
    OffsetDateTime createdAt
) {
  public static LeadPhotoResponse fromEntity(LeadPhoto photo, String urlPrefix) {
    String url = urlPrefix.replaceAll("/+$", "") + "/" + photo.getRelativePath();
    return new LeadPhotoResponse(
        photo.getId(),
        photo.getLeadId(),
        photo.getProviderId(),
        url,
        photo.getOriginalName(),
        photo.getContentType(),
        photo.getSizeBytes(),
        photo.getCaption(),
        photo.getCreatedAt()
    );
  }
}
