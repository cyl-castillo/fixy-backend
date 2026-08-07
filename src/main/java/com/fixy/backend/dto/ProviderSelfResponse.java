package com.fixy.backend.dto;

import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.Provider;
import java.util.List;

public record ProviderSelfResponse(
    Long id,
    String name,
    String phone,
    String primaryZone,
    String coverageZones,
    String description,
    String city,
    String categories,
    Integer acceptedJobsCount,
    Integer completedJobsCount,
    Integer rejectedJobsCount,
    String status,
    Double ratingAverage,
    Integer ratingCount,
    Boolean acceptingWork,
    /** Email de la cuenta Google vinculada para login sin link magico — null si nunca vinculo. */
    String googleEmail,
    List<ProviderAssignedLeadSummary> assignedLeads,
    /** Historial "no concretados": oportunidades que el proveedor soltó (ver ProviderDeclinedLeadSummary). */
    List<ProviderDeclinedLeadSummary> declinedLeads,
    /**
     * Zonas que el proveedor declaró y Fixy no reconoce: NO le van a traer
     * pedidos. Vacío en el caso normal. Ver {@link CoverageZone#unrecognized}
     * — el panel las muestra para que se entere él y no las descubra ops seis
     * semanas después mirando por qué no le llega trabajo.
     */
    List<String> unrecognizedZones
) {
  public static ProviderSelfResponse fromEntity(Provider provider, List<ProviderAssignedLeadSummary> leads) {
    return fromEntity(provider, leads, List.of());
  }

  public static ProviderSelfResponse fromEntity(
      Provider provider,
      List<ProviderAssignedLeadSummary> leads,
      List<ProviderDeclinedLeadSummary> declinedLeads) {
    Integer ratingCount = provider.getRatingCount() == null ? 0 : provider.getRatingCount();
    // Sin calificaciones todavía: no forzar 0.0, que el front distinga
    // "nuevo en Fixy" de una nota real de 0.
    Double ratingAverage = ratingCount == 0 ? null : provider.getRatingAverage();
    return new ProviderSelfResponse(
        provider.getId(),
        provider.getName(),
        provider.getPhone(),
        provider.getPrimaryZone(),
        provider.getCoverageZones(),
        provider.getDescription(),
        provider.getCity(),
        provider.getCategories(),
        provider.getAcceptedJobsCount(),
        provider.getCompletedJobsCount(),
        provider.getRejectedJobsCount(),
        provider.getStatus() == null ? null : provider.getStatus().name(),
        ratingAverage,
        ratingCount,
        provider.getAcceptingWork() == null || provider.getAcceptingWork(),
        provider.getGoogleEmail(),
        leads,
        declinedLeads,
        CoverageZone.unrecognized(provider.getPrimaryZone(), provider.getCoverageZones())
    );
  }
}
