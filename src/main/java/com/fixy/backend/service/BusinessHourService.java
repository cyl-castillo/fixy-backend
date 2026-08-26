package com.fixy.backend.service;

import com.fixy.backend.dto.BusinessHourRequest;
import com.fixy.backend.dto.BusinessHourResponse;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessHour;
import com.fixy.backend.repository.BusinessHourRepository;
import com.fixy.backend.repository.BusinessRepository;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Horarios de un comercio (Fase 1 de la ficha, V24). El {@code PUT} SIEMPRE
 * reemplaza el set completo — no hay edición fila a fila (ver diseño del
 * gap analysis §1: varias franjas por día son válidas, ej. horario partido).
 */
@Service
public class BusinessHourService {

  private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

  private final BusinessHourRepository businessHourRepository;
  private final BusinessRepository businessRepository;
  private final BusinessTimelineService businessTimelineService;

  public BusinessHourService(
      BusinessHourRepository businessHourRepository,
      BusinessRepository businessRepository,
      BusinessTimelineService businessTimelineService
  ) {
    this.businessHourRepository = businessHourRepository;
    this.businessRepository = businessRepository;
    this.businessTimelineService = businessTimelineService;
  }

  public List<BusinessHourResponse> list(Long businessId) {
    findBusiness(businessId);
    return businessHourRepository.findByBusinessIdOrderByDayOfWeekAscOpensAtAsc(businessId).stream()
        .map(this::toResponse)
        .toList();
  }

  public List<BusinessHourResponse> replace(Long businessId, List<BusinessHourRequest> requests) {
    Business business = findBusiness(businessId);
    List<BusinessHourRequest> safeRequests = requests == null ? List.of() : requests;
    safeRequests.forEach(this::validate);

    businessHourRepository.deleteByBusinessId(businessId);
    List<BusinessHour> saved = safeRequests.stream()
        .map(request -> toEntity(business, request))
        .map(businessHourRepository::save)
        .toList();

    businessTimelineService.appendEvent(businessId, "HOURS_UPDATED", "admin",
        saved.size() + " franja(s) horaria(s) reemplazadas");

    return businessHourRepository.findByBusinessIdOrderByDayOfWeekAscOpensAtAsc(businessId).stream()
        .map(this::toResponse)
        .toList();
  }

  private void validate(BusinessHourRequest request) {
    if (request.dayOfWeek() < 1 || request.dayOfWeek() > 7) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dayOfWeek must be 1-7 (ISO, lunes=1)");
    }
    if (!HHMM.matcher(request.opensAt()).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "opensAt must be HH:mm");
    }
    if (!HHMM.matcher(request.closesAt()).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "closesAt must be HH:mm");
    }
    if (request.opensAt().compareTo(request.closesAt()) >= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "opensAt must be before closesAt");
    }
  }

  private BusinessHour toEntity(Business business, BusinessHourRequest request) {
    BusinessHour hour = new BusinessHour();
    hour.setBusiness(business);
    hour.setDayOfWeek(request.dayOfWeek().shortValue());
    hour.setOpensAt(request.opensAt());
    hour.setClosesAt(request.closesAt());
    hour.setNote(trimToNull(request.note()));
    return hour;
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private Business findBusiness(Long businessId) {
    return businessRepository.findById(businessId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "business not found"));
  }

  private BusinessHourResponse toResponse(BusinessHour hour) {
    return new BusinessHourResponse(
        hour.getId(),
        hour.getBusiness().getId(),
        hour.getDayOfWeek(),
        hour.getOpensAt(),
        hour.getClosesAt(),
        hour.getNote()
    );
  }
}
