package com.fixy.backend.service;

import com.fixy.backend.dto.BusinessCreateRequest;
import com.fixy.backend.dto.BusinessPanelLinkResponse;
import com.fixy.backend.dto.BusinessResponse;
import com.fixy.backend.dto.BusinessUpdateRequest;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessRepository;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
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
  private final BusinessTimelineService businessTimelineService;
  private final String publicAppBaseUrl;
  private final SecureRandom random = new SecureRandom();

  public BusinessService(
      BusinessRepository businessRepository,
      BusinessTimelineService businessTimelineService,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl
  ) {
    this.businessRepository = businessRepository;
    this.businessTimelineService = businessTimelineService;
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
    business.setLatitude(request.latitude());
    business.setLongitude(request.longitude());
    business.setDescription(trimToNull(request.description()));
    business.setCategories(trimToNull(request.categories()));
    business.setStatus(BusinessStatus.ACTIVE);
    return toResponse(businessRepository.save(business));
  }

  public BusinessResponse update(Long id, BusinessUpdateRequest request) {
    Business business = findBusiness(id);
    List<String> changes = new ArrayList<>();

    if (request.name() != null) {
      applyIfChanged(changes, "name", business.getName(), request.name().trim(), business::setName);
    }
    if (request.whatsappNumber() != null) {
      applyIfChanged(changes, "whatsappNumber", business.getWhatsappNumber(),
          request.whatsappNumber().trim(), business::setWhatsappNumber);
    }
    if (request.category() != null) {
      applyIfChanged(changes, "category", business.getCategory(), request.category().trim(), business::setCategory);
    }
    if (request.primaryZone() != null) {
      applyIfChanged(changes, "primaryZone", business.getPrimaryZone(),
          trimToNull(request.primaryZone()), business::setPrimaryZone);
    }
    if (request.status() != null) {
      applyIfChanged(changes, "status",
          business.getStatus() == null ? null : business.getStatus().name(),
          request.status().name(), value -> business.setStatus(request.status()));
    }
    if (request.providerId() != null) {
      applyIfChanged(changes, "providerId",
          business.getProviderId() == null ? null : business.getProviderId().toString(),
          request.providerId().toString(), value -> business.setProviderId(request.providerId()));
    }
    if (request.address() != null) {
      applyIfChanged(changes, "address", business.getAddress(), trimToNull(request.address()), business::setAddress);
    }
    if (request.latitude() != null) {
      business.setLatitude(request.latitude());
    }
    if (request.longitude() != null) {
      business.setLongitude(request.longitude());
    }
    if (request.description() != null) {
      applyIfChanged(changes, "description", business.getDescription(),
          trimToNull(request.description()), business::setDescription);
    }
    if (request.categories() != null) {
      applyIfChanged(changes, "categories", business.getCategories(),
          normalizeCsv(request.categories()), business::setCategories);
    }

    Business saved = businessRepository.save(business);
    if (!changes.isEmpty()) {
      businessTimelineService.appendEvent(saved.getId(), "FICHA_UPDATED", "admin", String.join("; ", changes));
    }
    return toResponse(saved);
  }

  /** Aplica el nuevo valor y registra "campo: viejo → nuevo" en {@code
   * changes} solo si realmente cambió — evita eventos vacíos en un PATCH que
   * reenvía el mismo valor que ya tenía. */
  private void applyIfChanged(
      List<String> changes, String field, String oldValue, String newValue, java.util.function.Consumer<String> setter
  ) {
    setter.accept(newValue);
    if (!Objects.equals(oldValue, newValue)) {
      changes.add(field + ": " + describe(oldValue) + " → " + describe(newValue));
    }
  }

  private String describe(String value) {
    return value == null || value.isBlank() ? "(vacío)" : value;
  }

  private String normalizeCsv(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    List<String> parts = java.util.Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
    return parts.isEmpty() ? null : String.join(", ", parts);
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
        business.getPanelToken(),
        business.getDescription(),
        business.getCategories()
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
