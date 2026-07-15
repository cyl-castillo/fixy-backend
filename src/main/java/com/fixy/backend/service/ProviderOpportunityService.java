package com.fixy.backend.service;

import com.fixy.backend.dto.ProviderAssignedLeadSummary;
import com.fixy.backend.dto.ProviderOpportunitySummary;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderLeadDecline;
import com.fixy.backend.repository.LeadPhotoRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bandeja de oportunidades del proveedor: qué leads nuevos le sirven, y
 * las acciones de aceptar/rechazar. Antes de esto el proveedor solo veía
 * leads YA asignados ({@link ProviderSelfService#assignedLeadsFor}) — el
 * aviso de oportunidades nuevas dependía 100% de WhatsApp, que en prod no
 * está configurado, así que no había forma real de tomar un trabajo.
 */
@Service
public class ProviderOpportunityService {

  private static final Set<LeadStatus> OPEN_STATUSES =
      Set.of(LeadStatus.NEW, LeadStatus.IN_REVIEW, LeadStatus.PROVIDER_CONTACTED);

  /** Orden de urgencia declarada por el cliente, mayor primero. Valores
   * canónicos persistidos por {@code AgentService.normalizeUrgency}: alta,
   * media, baja. Cualquier otro valor (null, dato legacy) va al final. */
  private static final Map<String, Integer> URGENCY_RANK = Map.of(
      "alta", 3,
      "media", 2,
      "baja", 1
  );

  private final LeadRepository leadRepository;
  private final ProviderLeadDeclineRepository declineRepository;
  private final LeadPhotoRepository photoRepository;
  private final ProviderCatalogService catalogService;
  private final LeadAssignmentService leadAssignmentService;
  private final LeadTimelineService timelineService;

  public ProviderOpportunityService(
      LeadRepository leadRepository,
      ProviderLeadDeclineRepository declineRepository,
      LeadPhotoRepository photoRepository,
      ProviderCatalogService catalogService,
      LeadAssignmentService leadAssignmentService,
      LeadTimelineService timelineService
  ) {
    this.leadRepository = leadRepository;
    this.declineRepository = declineRepository;
    this.photoRepository = photoRepository;
    this.catalogService = catalogService;
    this.leadAssignmentService = leadAssignmentService;
    this.timelineService = timelineService;
  }

  public List<ProviderOpportunitySummary> listFor(Provider provider) {
    // Disponibilidad MVP: en pausa (acceptingWork=false) no ve oportunidades
    // nuevas. No afecta trabajos ya asignados (assignedLeadsFor).
    if (provider.getAcceptingWork() != null && !provider.getAcceptingWork()) {
      return List.of();
    }

    Set<Long> declinedLeadIds = declineRepository.findByProviderId(provider.getId()).stream()
        .map(ProviderLeadDecline::getLeadId)
        .collect(java.util.stream.Collectors.toSet());

    return leadRepository.findAllByOrderByCreatedAtDesc().stream()
        .filter(Lead::isReadyForMatching)
        .filter(lead -> OPEN_STATUSES.contains(lead.getStatus()))
        .filter(lead -> lead.getAssignedProviderId() == null)
        .filter(lead -> !declinedLeadIds.contains(lead.getId()))
        .filter(lead -> catalogService.matchesProvider(provider, lead.getDetectedCategory(), lead.getLocation()))
        .sorted(Comparator
            .comparingInt((Lead lead) -> urgencyRank(lead.getUrgency())).reversed()
            .thenComparing(Lead::getCreatedAt, Comparator.reverseOrder()))
        .map(lead -> ProviderOpportunitySummary.fromEntity(lead, photoRepository.countByLeadId(lead.getId())))
        .toList();
  }

  /**
   * El primero que acepta gana (ver {@link LeadAssignmentService}). El
   * ganador recibe el lead completo con datos de contacto y accessToken
   * para operar el chat, igual que en {@code assignedLeads}.
   */
  public ProviderAssignedLeadSummary accept(Provider provider, Long leadId) {
    requireVisibleOpportunity(provider, leadId);
    Lead assigned = leadAssignmentService.acceptForProvider(leadId, provider,
        provider.getName() + " tomó el trabajo desde su panel");
    return ProviderAssignedLeadSummary.fromEntity(assigned);
  }

  /** Idempotente: rechazar dos veces la misma oportunidad no falla. */
  public void decline(Provider provider, Long leadId) {
    if (!leadRepository.existsById(leadId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found");
    }
    if (declineRepository.existsByLeadIdAndProviderId(leadId, provider.getId())) {
      return;
    }
    ProviderLeadDecline decline = new ProviderLeadDecline();
    decline.setLeadId(leadId);
    decline.setProviderId(provider.getId());
    declineRepository.save(decline);

    Lead lead = leadRepository.findById(leadId).orElse(null);
    if (lead != null) {
      timelineService.appendEvent(lead, "PROVIDER_DECLINED", "provider",
          provider.getName() + " rechazó la oportunidad");
    }
  }

  /**
   * Antes de aceptar, verificamos que el lead exista y no le pertenezca ya
   * a otro flujo fuera de alcance de este proveedor (404 lead inexistente).
   * El chequeo de "¿sigue libre?" es responsabilidad del UPDATE atómico en
   * {@link LeadAssignmentService#acceptForProvider}, no de este método —
   * evita un check-then-act en memoria que reintroduciría la race.
   */
  private void requireVisibleOpportunity(Provider provider, Long leadId) {
    if (!leadRepository.existsById(leadId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found");
    }
  }

  private int urgencyRank(String urgency) {
    if (urgency == null) return 0;
    return URGENCY_RANK.getOrDefault(urgency.trim().toLowerCase(java.util.Locale.ROOT), 0);
  }
}
