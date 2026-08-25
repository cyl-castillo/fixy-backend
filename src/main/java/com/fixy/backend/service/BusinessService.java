package com.fixy.backend.service;

import com.fixy.backend.dto.BusinessCreateRequest;
import com.fixy.backend.dto.BusinessPanelLinkResponse;
import com.fixy.backend.dto.BusinessResponse;
import com.fixy.backend.dto.BusinessUpdateRequest;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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

  /** 32 bytes → 43 chars en base64url sin padding, mismo orden de magnitud
   * que el {@code accessToken} de proveedor (UUID sin guiones, 32 chars) pero
   * generado con {@link SecureRandom} en vez de {@code UUID.randomUUID()}
   * (que no es criptográficamente fuerte) — este token vive en una URL
   * pública sin ningún otro factor de auth, así que el espacio de búsqueda
   * tiene que ser inadivinable de verdad. */
  private static final int PANEL_TOKEN_BYTES = 32;

  private final BusinessRepository businessRepository;
  private final String publicAppBaseUrl;
  private final SecureRandom random = new SecureRandom();

  public BusinessService(
      BusinessRepository businessRepository,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl
  ) {
    this.businessRepository = businessRepository;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
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

  /**
   * Link del panel self-service del dueño (Fase 5, sin password): lazy — se
   * genera la primera vez que ops lo pide, nunca en el alta del comercio. Si
   * ya existe un token, se devuelve el mismo link SIN regenerar: rotar el
   * token invalidaría el que el comerciante ya guardó en su celular.
   */
  public BusinessPanelLinkResponse ensurePanelLink(Long id) {
    Business business = findBusiness(id);
    if (business.getPanelToken() == null || business.getPanelToken().isBlank()) {
      business.setPanelToken(newPanelToken());
      business = businessRepository.save(business);
    }
    return new BusinessPanelLinkResponse(publicAppBaseUrl + "/mi-comercio/" + business.getPanelToken());
  }

  private String newPanelToken() {
    byte[] buf = new byte[PANEL_TOKEN_BYTES];
    random.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
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
        business.getUpdatedAt(),
        business.getPanelToken()
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
