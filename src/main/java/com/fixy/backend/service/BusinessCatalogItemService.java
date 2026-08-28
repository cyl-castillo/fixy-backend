package com.fixy.backend.service;

import com.fixy.backend.dto.BusinessCatalogItemCreateRequest;
import com.fixy.backend.dto.BusinessCatalogItemResponse;
import com.fixy.backend.dto.BusinessCatalogItemUpdateRequest;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessCatalogItem;
import com.fixy.backend.model.BusinessCatalogItemConfidence;
import com.fixy.backend.model.BusinessCatalogItemKind;
import com.fixy.backend.repository.BusinessCatalogItemRepository;
import com.fixy.backend.repository.BusinessRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD admin del catálogo estructurado de la ficha (Fase 1, V24) —
 * rubros/marcas/productos con nivel de {@link BusinessCatalogItemConfidence}.
 * Regla central (gap analysis 2026-08-25 §1): cuando la confianza PASA A
 * {@code CONFIRMADO} (transición, no cada guardado que ya estaba
 * confirmado) el server estampa {@code verifiedAt = now}; si después deja
 * de ser {@code CONFIRMADO}, el timestamp NO se borra — queda como
 * histórico de la última verificación real.
 */
@Service
public class BusinessCatalogItemService {

  private final BusinessCatalogItemRepository catalogItemRepository;
  private final BusinessRepository businessRepository;
  private final BusinessTimelineService businessTimelineService;

  public BusinessCatalogItemService(
      BusinessCatalogItemRepository catalogItemRepository,
      BusinessRepository businessRepository,
      BusinessTimelineService businessTimelineService
  ) {
    this.catalogItemRepository = catalogItemRepository;
    this.businessRepository = businessRepository;
    this.businessTimelineService = businessTimelineService;
  }

  public List<BusinessCatalogItemResponse> list(Long businessId) {
    findBusiness(businessId);
    return catalogItemRepository.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional
  public BusinessCatalogItemResponse create(Long businessId, BusinessCatalogItemCreateRequest request) {
    return create(businessId, request, "admin", null);
  }

  /**
   * Alta desde el panel del dueño (Fase 2 del panel self-service): mismo
   * body que el {@code POST} admin, pero la confianza queda SIEMPRE
   * {@code CONFIRMADO} — el dueño es la autoridad sobre su propio catálogo,
   * no hace falta que otro humano lo valide — y el evento de timeline queda
   * con actor {@code owner} (mismo valor que {@code
   * BusinessInquiryService.answerAsOwner}) para que se distinga en la ficha
   * admin qué cambió el comerciante.
   */
  @Transactional
  public BusinessCatalogItemResponse createAsOwner(Long businessId, BusinessCatalogItemCreateRequest request) {
    return create(businessId, request, "owner", BusinessCatalogItemConfidence.CONFIRMADO);
  }

  private BusinessCatalogItemResponse create(
      Long businessId, BusinessCatalogItemCreateRequest request, String actor,
      BusinessCatalogItemConfidence forcedConfidence
  ) {
    Business business = findBusiness(businessId);
    BusinessCatalogItemKind kind = parseKind(request.kind());
    BusinessCatalogItemConfidence confidence =
        forcedConfidence != null ? forcedConfidence : parseConfidence(request.confidence());
    Integer priceFrom = validatePriceFrom(request.priceFrom());

    BusinessCatalogItem item = new BusinessCatalogItem();
    item.setBusiness(business);
    item.setLabel(request.label().trim());
    item.setKind(kind);
    item.setPriceFrom(priceFrom);
    item.setConfidence(confidence);
    item.setNotes(trimToNull(request.notes()));
    item.setActive(true);
    if (confidence == BusinessCatalogItemConfidence.CONFIRMADO) {
      item.setVerifiedAt(OffsetDateTime.now());
    }

    BusinessCatalogItem saved = catalogItemRepository.save(item);
    businessTimelineService.appendEvent(businessId, "CATALOG_ITEM_ADDED", actor,
        saved.getLabel() + " (" + kind + ", " + confidence + ")");
    return toResponse(saved);
  }

  @Transactional
  public BusinessCatalogItemResponse update(Long businessId, Long itemId, BusinessCatalogItemUpdateRequest request) {
    return update(businessId, itemId, request, "admin", null);
  }

  /** Igual que {@link #createAsOwner} pero para {@code PUT}: la confianza
   * pasa SIEMPRE a {@code CONFIRMADO}, sin importar lo que venga en el body. */
  @Transactional
  public BusinessCatalogItemResponse updateAsOwner(Long businessId, Long itemId, BusinessCatalogItemUpdateRequest request) {
    return update(businessId, itemId, request, "owner", BusinessCatalogItemConfidence.CONFIRMADO);
  }

  private BusinessCatalogItemResponse update(
      Long businessId, Long itemId, BusinessCatalogItemUpdateRequest request, String actor,
      BusinessCatalogItemConfidence forcedConfidence
  ) {
    findBusiness(businessId);
    BusinessCatalogItem item = findItem(businessId, itemId);

    BusinessCatalogItemKind kind = parseKind(request.kind());
    BusinessCatalogItemConfidence confidence =
        forcedConfidence != null ? forcedConfidence : parseConfidence(request.confidence());
    Integer priceFrom = validatePriceFrom(request.priceFrom());
    boolean wasConfirmed = item.getConfidence() == BusinessCatalogItemConfidence.CONFIRMADO;

    item.setLabel(request.label().trim());
    item.setKind(kind);
    item.setPriceFrom(priceFrom);
    item.setConfidence(confidence);
    item.setNotes(trimToNull(request.notes()));
    item.setActive(Boolean.TRUE.equals(request.active()));
    if (confidence == BusinessCatalogItemConfidence.CONFIRMADO && !wasConfirmed) {
      item.setVerifiedAt(OffsetDateTime.now());
    }
    // si deja de ser CONFIRMADO, verifiedAt queda tal cual (histórico, no se borra).

    BusinessCatalogItem saved = catalogItemRepository.save(item);
    businessTimelineService.appendEvent(businessId, "CATALOG_ITEM_UPDATED", actor,
        saved.getLabel() + " (" + kind + ", " + confidence + ", active=" + saved.isActive() + ")");
    return toResponse(saved);
  }

  /** Soft delete idempotente: si ya estaba inactivo, no genera un evento
   * nuevo ni falla — repetir el DELETE deja el mismo estado final. */
  @Transactional
  public void delete(Long businessId, Long itemId) {
    delete(businessId, itemId, "admin");
  }

  /** Igual que {@link #delete} pero con actor {@code owner} en la timeline. */
  @Transactional
  public void deleteAsOwner(Long businessId, Long itemId) {
    delete(businessId, itemId, "owner");
  }

  private void delete(Long businessId, Long itemId, String actor) {
    findBusiness(businessId);
    BusinessCatalogItem item = findItem(businessId, itemId);
    if (!item.isActive()) {
      return;
    }
    item.setActive(false);
    BusinessCatalogItem saved = catalogItemRepository.save(item);
    businessTimelineService.appendEvent(businessId, "CATALOG_ITEM_REMOVED", actor, saved.getLabel());
  }

  private BusinessCatalogItemKind parseKind(String raw) {
    try {
      return BusinessCatalogItemKind.valueOf(normalize(raw));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported kind");
    }
  }

  private BusinessCatalogItemConfidence parseConfidence(String raw) {
    try {
      return BusinessCatalogItemConfidence.valueOf(normalize(raw));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported confidence");
    }
  }

  private Integer validatePriceFrom(Integer priceFrom) {
    if (priceFrom != null && priceFrom < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "priceFrom must be >= 0");
    }
    return priceFrom;
  }

  private String normalize(String raw) {
    return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
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

  private BusinessCatalogItem findItem(Long businessId, Long itemId) {
    return catalogItemRepository.findByIdAndBusinessId(itemId, businessId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "catalog item not found"));
  }

  private BusinessCatalogItemResponse toResponse(BusinessCatalogItem item) {
    return new BusinessCatalogItemResponse(
        item.getId(),
        item.getBusiness().getId(),
        item.getLabel(),
        item.getKind().name(),
        item.getPriceFrom(),
        item.getConfidence().name(),
        item.getVerifiedAt(),
        item.getNotes(),
        item.isActive(),
        item.getCreatedAt(),
        item.getUpdatedAt()
    );
  }
}
