package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * API self-service del proveedor: el proveedor accede con
 * (providerId, accessToken) y opera sus leads asignados.
 *
 * Auth simple: Carlos (ops) genera el token vía
 * {@code POST /api/providers/{id}/access-token} y comparte la URL
 * {@code https://www.fixy.com.uy/p/{id}/{token}} con el proveedor por
 * WhatsApp manual.
 */
@Service
public class ProviderSelfService {

  /** Statuses que el proveedor puede setear desde su panel. */
  private static final Set<LeadStatus> PROVIDER_TRANSITIONS = Set.of(
      LeadStatus.ASSIGNED,
      LeadStatus.IN_PROGRESS,
      LeadStatus.COMPLETED,
      LeadStatus.CANCELLED
  );

  private final ProviderRepository providerRepository;
  private final LeadRepository leadRepository;
  private final LeadTimelineService timelineService;
  private final CommissionService commissionService;
  private final LeadClosingService leadClosingService;
  private final boolean paymentsEnabled;

  public ProviderSelfService(
      ProviderRepository providerRepository,
      LeadRepository leadRepository,
      LeadTimelineService timelineService,
      CommissionService commissionService,
      LeadClosingService leadClosingService,
      @Value("${fixy.payments.enabled:false}") boolean paymentsEnabled
  ) {
    this.providerRepository = providerRepository;
    this.leadRepository = leadRepository;
    this.timelineService = timelineService;
    this.commissionService = commissionService;
    this.leadClosingService = leadClosingService;
    this.paymentsEnabled = paymentsEnabled;
  }

  public Provider authenticate(Long providerId, String token) {
    Provider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    if (provider.getAccessToken() == null
        || token == null
        || !provider.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    return provider;
  }

  public List<Lead> assignedLeadsFor(Provider provider) {
    // Union: por ID (asignaciones nuevas) y por nombre (legacy).
    List<Lead> byId = leadRepository.findByAssignedProviderIdOrderByCreatedAtDesc(provider.getId());
    if (provider.getName() == null || provider.getName().isBlank()) {
      return byId;
    }
    List<Lead> byName = leadRepository.findByAssignedProviderIgnoreCaseOrderByCreatedAtDesc(provider.getName());
    if (byId.isEmpty()) return byName;
    if (byName.isEmpty()) return byId;
    java.util.LinkedHashMap<Long, Lead> merged = new java.util.LinkedHashMap<>();
    for (Lead l : byId) merged.put(l.getId(), l);
    for (Lead l : byName) merged.putIfAbsent(l.getId(), l);
    return new java.util.ArrayList<>(merged.values());
  }

  public Lead requireAssignedLead(Provider provider, Long leadId) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    Long assignedId = lead.getAssignedProviderId();
    if (assignedId != null && assignedId.equals(provider.getId())) {
      return lead;
    }
    String assigned = lead.getAssignedProvider();
    if (assigned != null && assigned.equalsIgnoreCase(provider.getName())) {
      return lead;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "lead not assigned to this provider");
  }

  public Lead updateLeadStatus(Provider provider, Long leadId, LeadStatus newStatus) {
    return updateLeadStatus(provider, leadId, newStatus, null);
  }

  /**
   * @param amountCharged monto que el proveedor cobró al cliente. Con
   *                       {@code fixy.payments.enabled=true}, es obligatorio
   *                       (> 0) para transicionar a COMPLETED — dispara la
   *                       creación de la comisión (H1.2/H1.3). Con el flag en
   *                       false, se ignora y el comportamiento es el mismo de
   *                       siempre (rollback seguro sin credenciales de MP).
   */
  public Lead updateLeadStatus(Provider provider, Long leadId, LeadStatus newStatus, BigDecimal amountCharged) {
    if (!PROVIDER_TRANSITIONS.contains(newStatus)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "status not allowed for provider self-service");
    }
    if (paymentsEnabled && newStatus == LeadStatus.COMPLETED
        && (amountCharged == null || amountCharged.signum() <= 0)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "para marcar el trabajo como completado necesitamos el monto cobrado al cliente (mayor a 0)");
    }
    Lead lead = requireAssignedLead(provider, leadId);
    LeadStatus before = lead.getStatus();
    if (before != newStatus) {
      lead.setStatus(newStatus);
      provider.setLastRespondedAt(OffsetDateTime.now());
      timelineService.appendEvent(lead, "PROVIDER_STATUS_CHANGE", "provider",
          "%s → %s".formatted(before, newStatus));
      // bumps de contadores en el provider para visibilidad ops
      switch (newStatus) {
        case ASSIGNED -> provider.setAcceptedJobsCount(safeInc(provider.getAcceptedJobsCount()));
        case CANCELLED -> provider.setRejectedJobsCount(safeInc(provider.getRejectedJobsCount()));
        case COMPLETED -> provider.setCompletedJobsCount(safeInc(provider.getCompletedJobsCount()));
        default -> { /* no counter */ }
      }
      leadRepository.save(lead);
      providerRepository.save(provider);
      if (paymentsEnabled && newStatus == LeadStatus.COMPLETED) {
        commissionService.createForCompletedLead(lead, provider, amountCharged);
      }
      if (newStatus == LeadStatus.COMPLETED) {
        // Un solo mensaje: si payments está ON, createForCompletedLead ya
        // mandó el aviso de comisión al proveedor (canal distinto, no
        // pisa este). Este es al cliente, pidiendo confirmación/rating.
        leadClosingService.notifyCustomerOfCompletion(lead);
      }
    }
    return lead;
  }

  public Provider regenerateAccessToken(Long providerId) {
    Provider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    provider.setAccessToken(UUID.randomUUID().toString().replace("-", ""));
    return providerRepository.save(provider);
  }

  private int safeInc(Integer value) {
    return value == null ? 1 : value + 1;
  }
}
