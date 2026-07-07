package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Camino único para "un proveedor toma un lead", usado tanto por el
 * webhook de WhatsApp ({@code WhatsAppWebhookController}) como por la
 * bandeja de oportunidades self-service ({@code ProviderOpportunityService}).
 * Antes de esta clase cada canal asignaba el lead a mano (set status +
 * assignedProvider + evento + mensaje al cliente) de forma independiente,
 * lo cual iba a divergir apenas uno de los dos cambiara.
 */
@Service
public class LeadAssignmentService {

  /** Statuses desde los que un lead puede pasar a ASSIGNED. */
  private static final List<LeadStatus> MATCHABLE_STATUSES =
      List.of(LeadStatus.NEW, LeadStatus.IN_REVIEW, LeadStatus.PROVIDER_CONTACTED);

  private final LeadRepository leadRepository;
  private final LeadTimelineService timelineService;
  private final LeadMessageService messageService;

  public LeadAssignmentService(
      LeadRepository leadRepository,
      LeadTimelineService timelineService,
      LeadMessageService messageService
  ) {
    this.leadRepository = leadRepository;
    this.timelineService = timelineService;
    this.messageService = messageService;
  }

  /**
   * Intenta asignar atómicamente el lead al proveedor. El primero que gana
   * la escritura en base es el que se queda con el lead: dos proveedores
   * pueden llamar esto casi simultáneamente (bandeja) y solo uno gana.
   *
   * @throws ResponseStatusException 404 si el lead no existe; 409 si el
   *     lead ya estaba asignado o cambió de status antes de esta llamada.
   */
  public Lead acceptForProvider(Long leadId, Provider provider, String eventDetail) {
    int updated = leadRepository.assignIfUnclaimed(
        leadId, provider.getId(), provider.getName(), MATCHABLE_STATUSES);
    if (updated == 0) {
      if (!leadRepository.existsById(leadId)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found");
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "otro proveedor tomó este trabajo primero");
    }

    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    timelineService.appendEvent(lead, "PROVIDER_ACCEPTED", "provider", eventDetail);
    messageService.postFromOps(lead.getId(), "fixy",
        "¡Buenas noticias! %s tomó tu pedido y te va a contactar en breve.".formatted(provider.getName()));
    return lead;
  }
}
