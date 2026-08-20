package com.fixy.backend.service;

import com.fixy.backend.dto.BusinessCreateRequest;
import com.fixy.backend.dto.BusinessResponse;
import com.fixy.backend.dto.BusinessUpdateRequest;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD admin de {@link Business} (diseño FIXY_OFERTAS_INGESTA_DESIGN.md §3.1).
 * Alta manual por ops nace {@code ACTIVE} directo — no hay flujo de
 * autoregistro en este alcance (eso queda para una futura Opción D).
 */
@Service
public class BusinessService {

  private final BusinessRepository businessRepository;

  public BusinessService(BusinessRepository businessRepository) {
    this.businessRepository = businessRepository;
  }

  public List<BusinessResponse> list() {
    return businessRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toResponse)
        .toList();
  }

  public BusinessResponse get(Long id) {
    return toResponse(findBusiness(id));
  }

  public BusinessResponse create(BusinessCreateRequest request) {
    Business business = new Business();
    business.setName(request.name().trim());
    business.setWhatsappNumber(request.whatsappNumber().trim());
    business.setCategory(request.category().trim());
    business.setPrimaryZone(trimToNull(request.primaryZone()));
    business.setProviderId(request.providerId());
    business.setAddress(trimToNull(request.address()));
    business.setStatus(BusinessStatus.ACTIVE);
    return toResponse(businessRepository.save(business));
  }

  public BusinessResponse update(Long id, BusinessUpdateRequest request) {
    Business business = findBusiness(id);

    if (request.name() != null) business.setName(request.name().trim());
    if (request.whatsappNumber() != null) business.setWhatsappNumber(request.whatsappNumber().trim());
    if (request.category() != null) business.setCategory(request.category().trim());
    if (request.primaryZone() != null) business.setPrimaryZone(trimToNull(request.primaryZone()));
    if (request.status() != null) business.setStatus(request.status());
    if (request.providerId() != null) business.setProviderId(request.providerId());
    if (request.address() != null) business.setAddress(trimToNull(request.address()));
    if (request.latitude() != null) business.setLatitude(request.latitude());
    if (request.longitude() != null) business.setLongitude(request.longitude());

    return toResponse(businessRepository.save(business));
  }

  private Business findBusiness(Long id) {
    return businessRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "business not found"));
  }

  private BusinessResponse toResponse(Business business) {
    return new BusinessResponse(
        business.getId(),
        business.getName(),
        business.getWhatsappNumber(),
        business.getCategory(),
        business.getPrimaryZone(),
        business.getStatus(),
        business.getProviderId(),
        business.getAddress(),
        business.getLatitude(),
        business.getLongitude(),
        business.getCreatedAt(),
        business.getUpdatedAt()
    );
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }
}
