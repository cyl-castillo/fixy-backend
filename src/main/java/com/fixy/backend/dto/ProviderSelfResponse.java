package com.fixy.backend.dto;

import com.fixy.backend.model.Provider;
import java.util.List;

public record ProviderSelfResponse(
    Long id,
    String name,
    String phone,
    String primaryZone,
    String coverageZones,
    String city,
    String categories,
    Integer acceptedJobsCount,
    Integer completedJobsCount,
    Integer rejectedJobsCount,
    String status,
    List<ProviderAssignedLeadSummary> assignedLeads
) {
  public static ProviderSelfResponse fromEntity(Provider provider, List<ProviderAssignedLeadSummary> leads) {
    return new ProviderSelfResponse(
        provider.getId(),
        provider.getName(),
        provider.getPhone(),
        provider.getPrimaryZone(),
        provider.getCoverageZones(),
        provider.getCity(),
        provider.getCategories(),
        provider.getAcceptedJobsCount(),
        provider.getCompletedJobsCount(),
        provider.getRejectedJobsCount(),
        provider.getStatus() == null ? null : provider.getStatus().name(),
        leads
    );
  }
}
