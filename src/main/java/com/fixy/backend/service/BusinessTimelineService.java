package com.fixy.backend.service;

import com.fixy.backend.dto.BusinessEventResponse;
import com.fixy.backend.model.BusinessEvent;
import com.fixy.backend.repository.BusinessEventRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Timeline append-only de la ficha de un comercio (Fase 1, V24) — espejo
 * exacto de {@link LeadTimelineService}: solo inserta, nunca update/delete.
 */
@Service
public class BusinessTimelineService {

  private static final int EVENTS_LIMIT = 100;

  private final BusinessEventRepository businessEventRepository;

  public BusinessTimelineService(BusinessEventRepository businessEventRepository) {
    this.businessEventRepository = businessEventRepository;
  }

  public void appendEvent(Long businessId, String type, String actor, String message) {
    BusinessEvent event = new BusinessEvent();
    event.setBusinessId(businessId);
    event.setType(type);
    event.setActor(actor);
    event.setMessage(message);
    businessEventRepository.save(event);
  }

  /** Timeline descendente, limit 100 (ver GET /api/businesses/{id}/events). */
  public List<BusinessEventResponse> listForBusiness(Long businessId) {
    return businessEventRepository
        .findByBusinessIdOrderByCreatedAtDesc(businessId, PageRequest.of(0, EVENTS_LIMIT))
        .stream()
        .map(event -> new BusinessEventResponse(
            event.getId(),
            event.getType(),
            event.getActor(),
            event.getMessage(),
            event.getCreatedAt()
        ))
        .toList();
  }

  /** Mismo chequeo de idempotencia que {@link LeadTimelineService#hasEvent},
   * expuesto por si algún servicio necesita "¿ya se registró esto?" sin
   * acoplarse directo a BusinessEventRepository. */
  public boolean hasEvent(Long businessId, String type) {
    return businessEventRepository
        .findByBusinessIdOrderByCreatedAtDesc(businessId, PageRequest.of(0, EVENTS_LIMIT))
        .stream()
        .anyMatch(event -> event.getType().equals(type));
  }
}
