package com.fixy.backend.service;

import com.fixy.backend.dto.BusinessCreateRequest;
import com.fixy.backend.dto.BusinessPanelLinkResponse;
import com.fixy.backend.dto.BusinessPublicLinkResponse;
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

  /** Límite de {@code description} solo cuando la edita el dueño desde su
   * panel (ver {@link #updateDescriptionAsOwner}) — el PATCH admin no lo
   * valida. */
  private static final int MAX_OWNER_DESCRIPTION_LENGTH = 500;

  private final BusinessRepository businessRepository;
  private final BusinessTimelineService businessTimelineService;
  private final BusinessSlugService businessSlugService;
  private final String publicAppBaseUrl;
  private final SecureRandom random = new SecureRandom();

  public BusinessService(
      BusinessRepository businessRepository,
      BusinessTimelineService businessTimelineService,
      BusinessSlugService businessSlugService,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl
  ) {
    this.businessRepository = businessRepository;
    this.businessTimelineService = businessTimelineService;
    this.businessSlugService = businessSlugService;
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
    Business saved = businessRepository.save(business);
    // Slug de la página pública (Fase 3, V26): se asigna en el alta, no
    // lazy-solo-a-pedido como panelToken — así un comercio recién creado ya
    // tiene URL pública estable desde el día 1 (ver gap analysis §6).
    businessSlugService.ensureSlug(saved);
    return toResponse(saved);
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

  /**
   * PATCH de descripción desde el panel del dueño (Fase 2 del panel
   * self-service): a diferencia del PATCH admin ({@link #update}, cualquier
   * campo de {@link BusinessUpdateRequest}, sin validar longitud — ops es
   * confiable), acá el dueño SOLO puede tocar {@code description}
   * (categories/matching queda territorio admin, no se expone este método
   * para eso) y el server valida el límite de 500 caracteres. Reusa {@link
   * #applyIfChanged} para no duplicar el patrón "campo: viejo → nuevo" de la
   * timeline; actor {@code owner} (mismo valor que {@code
   * BusinessInquiryService.answerAsOwner}) para distinguir en la ficha admin
   * qué cambió el comerciante. {@code null} borra la descripción.
   */
  public String updateDescriptionAsOwner(Long id, String description) {
    if (description != null && description.length() > MAX_OWNER_DESCRIPTION_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description must be at most 500 characters");
    }
    Business business = findBusiness(id);
    List<String> changes = new ArrayList<>();
    applyIfChanged(changes, "description", business.getDescription(), trimToNull(description), business::setDescription);
    Business saved = businessRepository.save(business);
    if (!changes.isEmpty()) {
      businessTimelineService.appendEvent(saved.getId(), "FICHA_UPDATED", "owner", String.join("; ", changes));
    }
    return saved.getDescription();
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
    Business business = ensurePanel(findBusiness(id));
    return new BusinessPanelLinkResponse(publicAppBaseUrl + "/mi-comercio/" + business.getPanelToken());
  }

  /**
   * Igual que {@link #ensurePanelLink} pero devuelve el {@link Business} con
   * el token garantizado en vez de la URL armada — lo usa {@code
   * BusinessGoogleAuthService.login} (Fase 1) para no rotar el panelToken en
   * cada login, extraído de {@link #ensurePanelLink} para no duplicar la
   * lógica de "generar lazy, nunca regenerar".
   */
  public Business ensurePanel(Business business) {
    if (business.getPanelToken() == null || business.getPanelToken().isBlank()) {
      business.setPanelToken(newPanelToken());
      business = businessRepository.save(business);
    }
    return business;
  }

  /**
   * Link de la página pública del comercio (Fase 3, V26, patrón "public-link"
   * del gap analysis §7): idempotente vía {@code BusinessSlugService} — si el
   * comercio no tiene slug todavía (comercio dado de alta antes de esta fase)
   * lo genera acá; si ya lo tiene, devuelve la misma URL sin regenerar.
   */
  public BusinessPublicLinkResponse ensurePublicLink(Long id) {
    Business business = findBusiness(id);
    String slug = businessSlugService.ensureSlug(business);
    return new BusinessPublicLinkResponse(publicAppBaseUrl + "/comercio/" + slug);
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
        business.getCategories(),
        business.getSlug()
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
