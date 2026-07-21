package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lado cliente del "horario acordado con un toque" (ver
 * {@link ProviderSelfService#proposeSchedule}): el cliente responde la
 * última propuesta con Confirmar / No puedo, sin tipear. Todo el estado
 * vive en el timeline (SCHEDULE_PROPOSED/CONFIRMED/REJECTED con la
 * etiqueta cruda como message) — sin migración de esquema, mismo patrón
 * que PROVIDER_ON_THE_WAY que el frontend ya deriva de eventos.
 */
@Service
public class LeadScheduleService {

  public static final String SCHEDULE_PROPOSED_EVENT_TYPE = "SCHEDULE_PROPOSED";
  public static final String SCHEDULE_CONFIRMED_EVENT_TYPE = "SCHEDULE_CONFIRMED";
  public static final String SCHEDULE_REJECTED_EVENT_TYPE = "SCHEDULE_REJECTED";

  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final ProviderRepository providerRepository;
  private final LeadTimelineService timelineService;
  private final LeadMessageService messageService;
  private final PushNotificationService pushNotificationService;

  public LeadScheduleService(
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      ProviderRepository providerRepository,
      LeadTimelineService timelineService,
      LeadMessageService messageService,
      PushNotificationService pushNotificationService
  ) {
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.providerRepository = providerRepository;
    this.timelineService = timelineService;
    this.messageService = messageService;
    this.pushNotificationService = pushNotificationService;
  }

  /**
   * Responde la última propuesta pendiente. 409 si no hay propuesta o si ya
   * fue respondida (el doble toque no duplica eventos ni mensajes).
   */
  public Map<String, Object> respond(Long leadId, boolean accept) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));

    List<LeadEvent> proposals = leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, SCHEDULE_PROPOSED_EVENT_TYPE);
    if (proposals.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "no hay propuesta de horario pendiente");
    }
    LeadEvent proposal = proposals.get(0);
    if (isAlreadyAnswered(leadId, proposal.getCreatedAt())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "esa propuesta ya fue respondida");
    }

    String label = proposal.getMessage();
    if (accept) {
      timelineService.appendEvent(lead, SCHEDULE_CONFIRMED_EVENT_TYPE, "customer", label);
      messageService.postFromOps(leadId, "fixy", "✅ Horario confirmado: %s.".formatted(label));
      notifyProvider(lead, "El cliente confirmó el horario", "%s — quedó agendado.".formatted(label));
      return Map.of("status", "confirmed", "proposal", label);
    }
    timelineService.appendEvent(lead, SCHEDULE_REJECTED_EVENT_TYPE, "customer", label);
    messageService.postFromOps(leadId, "fixy",
        "Ese horario no le queda bien al cliente — coordinen otro por acá, o proponé uno nuevo desde tu panel.");
    notifyProvider(lead, "El horario propuesto no le sirve al cliente",
        "%s no le queda bien — proponé otro desde tu panel.".formatted(label));
    return Map.of("status", "rejected", "proposal", label);
  }

  /** true si ya existe una respuesta (confirmada o rechazada) posterior o simultánea a la propuesta. */
  private boolean isAlreadyAnswered(Long leadId, OffsetDateTime proposalCreatedAt) {
    if (proposalCreatedAt == null) {
      return false;
    }
    return List.of(SCHEDULE_CONFIRMED_EVENT_TYPE, SCHEDULE_REJECTED_EVENT_TYPE).stream()
        .flatMap(type -> leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, type).stream())
        .map(LeadEvent::getCreatedAt)
        .anyMatch(answeredAt -> answeredAt != null && !answeredAt.isBefore(proposalCreatedAt));
  }

  private void notifyProvider(Lead lead, String title, String body) {
    if (lead.getAssignedProviderId() == null) {
      return;
    }
    try {
      Provider provider = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
      if (provider != null) {
        pushNotificationService.notifyProvider(provider.getId(), provider.getAccessToken(), title, body);
      }
    } catch (Exception ex) {
      // best-effort, nunca debe romper la respuesta del cliente
    }
  }
}
