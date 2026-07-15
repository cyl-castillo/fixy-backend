package com.fixy.backend.service;

import com.fixy.backend.dto.LeadEventResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.repository.LeadEventRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LeadTimelineService {

  private final LeadEventRepository leadEventRepository;

  public LeadTimelineService(LeadEventRepository leadEventRepository) {
    this.leadEventRepository = leadEventRepository;
  }

  public void appendEvent(Lead lead, String type, String actor, String message) {
    LeadEvent event = new LeadEvent();
    event.setLead(lead);
    event.setType(type);
    event.setActor(actor);
    event.setMessage(message);
    leadEventRepository.save(event);
  }

  public List<LeadEventResponse> listForLead(Long leadId) {
    return leadEventRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .map(event -> new LeadEventResponse(
            event.getId(),
            event.getType(),
            event.getActor(),
            event.getMessage(),
            event.getCreatedAt()
        ))
        .toList();
  }

  /** Chequeo genérico de idempotencia: ¿ya se registró un evento de este tipo
   * para este lead? Mismo patrón que usa TelegramNotifyService internamente
   * para OPS_NOTIFIED_OPPORTUNITY; expuesto acá para que otros servicios
   * (ej. el dispatcher de acciones del agente) puedan chequear "ya pasó esto"
   * sin acoplarse directo a LeadEventRepository. */
  public boolean hasEvent(Long leadId, String type) {
    return !leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, type).isEmpty();
  }
}
