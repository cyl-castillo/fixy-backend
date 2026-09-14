package com.fixy.backend.service;

import com.fixy.backend.dto.ServiceCatalogGroupResponse;
import com.fixy.backend.dto.ServiceCatalogItemCreateRequest;
import com.fixy.backend.dto.ServiceCatalogItemResponse;
import com.fixy.backend.dto.ServiceCatalogItemUpdateRequest;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.model.ServiceCategory;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catálogo de servicios con precio cerrado (Refundación de Fixy, fase 1,
 * contrato REFUNDACION_FASE1_CONTRATO.md §1/§2). "Categorías activas para
 * pedir" ({@code fixy.orders.active-categories}) es el filtro que decide qué
 * aparece en el catálogo público y en la puerta de pedido estructurado — las
 * demás categorías siguen existiendo en datos, en el agente conversacional y
 * en el admin (no se tocan acá).
 */
@Service
public class ServiceCatalogService {

  private final ServiceCatalogItemRepository repository;
  private final Set<String> activeCategories;

  public ServiceCatalogService(
      ServiceCatalogItemRepository repository,
      @Value("${fixy.orders.active-categories:aires_acondicionados,plomeria,barometrica}") String activeCategoriesRaw
  ) {
    this.repository = repository;
    this.activeCategories = parseActiveCategories(activeCategoriesRaw);
  }

  /** "$2.900" como lo escribe un uruguayo: punto de miles, sin decimales. */
  public static String formatUyu(Integer amount) {
    if (amount == null) {
      return "";
    }
    return java.text.NumberFormat.getIntegerInstance(Locale.of("es", "UY")).format(amount);
  }

  private static Set<String> parseActiveCategories(String raw) {
    Set<String> result = new LinkedHashSet<>();
    if (raw == null) {
      return result;
    }
    for (String piece : raw.split(",")) {
      String trimmed = piece.trim().toLowerCase(Locale.ROOT);
      if (!trimmed.isBlank()) {
        result.add(trimmed);
      }
    }
    return result;
  }

  /** Categorías habilitadas para pedir (contrato §1). */
  public Set<String> activeCategories() {
    return activeCategories;
  }

  public boolean isCategoryActive(String category) {
    return category != null && activeCategories.contains(category.toLowerCase(Locale.ROOT).trim());
  }

  /**
   * Catálogo público agrupado por categoría, solo categorías activas y
   * servicios {@code active=true}, en el orden de {@code sortOrder}
   * (contrato §2). {@code categoryFilter} opcional restringe a una sola
   * categoría (debe además estar activa, si no la lista sale vacía).
   */
  public List<ServiceCatalogGroupResponse> publicCatalog(String categoryFilter) {
    String normalizedFilter = categoryFilter == null || categoryFilter.isBlank()
        ? null
        : categoryFilter.toLowerCase(Locale.ROOT).trim();

    Map<String, List<ServiceCatalogItem>> byCategory = new LinkedHashMap<>();
    // Recorremos las categorías activas en el orden de la config (no las
    // filas de la tabla ni el enum): así la primera del home es la que más
    // demanda tiene (aires), y una categoría activa sin servicios cargados
    // todavía sale vacía en vez de omitirse o tirar NPE.
    for (String id : activeCategories) {
      if (ServiceCategory.fromId(id).isEmpty()) {
        continue;
      }
      if (normalizedFilter != null && !normalizedFilter.equals(id)) {
        continue;
      }
      byCategory.put(id, repository.findByCategoryAndActiveTrueOrderBySortOrderAscIdAsc(id));
    }

    return byCategory.entrySet().stream()
        .map(entry -> new ServiceCatalogGroupResponse(
            entry.getKey(),
            ServiceCategory.humanLabel(entry.getKey()),
            entry.getValue().stream()
                .map(item -> new ServiceCatalogGroupResponse.Item(
                    item.getCode(), item.getName(), item.getDescription(),
                    item.getPriceFrom(), item.getPriceTo(), item.getDurationMin()))
                .toList()))
        .toList();
  }

  /**
   * Servicio orderable: existe, está activo, Y su categoría está entre las
   * activas para pedir (contrato §3, "serviceCode debe existir y estar
   * activo, en categoría activa"). Usado por el pedido estructurado y por
   * la tool MCP fixy_create_order — un mismo punto de validación.
   */
  public Optional<ServiceCatalogItem> findOrderable(String code) {
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    return repository.findByCodeAndActiveTrue(code.trim())
        .filter(item -> isCategoryActive(item.getCategory()));
  }

  // --- Admin CRUD (httpBasic + rol OPS, ver SecurityConfig) ---------------

  public List<ServiceCatalogItemResponse> adminList() {
    return repository.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toResponse).toList();
  }

  public ServiceCatalogItemResponse create(ServiceCatalogItemCreateRequest request) {
    if (repository.existsByCode(request.code().trim())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code already exists");
    }
    if (ServiceCategory.fromId(request.category()).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown category");
    }

    ServiceCatalogItem item = new ServiceCatalogItem();
    item.setCategory(request.category().trim());
    item.setCode(request.code().trim());
    item.setName(request.name().trim());
    item.setDescription(request.description());
    item.setPriceFrom(request.priceFrom());
    item.setPriceTo(request.priceTo());
    item.setDurationMin(request.durationMin());
    item.setActive(request.active() == null || request.active());
    item.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());

    return toResponse(repository.save(item));
  }

  public ServiceCatalogItemResponse update(Long id, ServiceCatalogItemUpdateRequest request) {
    ServiceCatalogItem item = repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "service not found"));

    if (request.category() != null) {
      if (ServiceCategory.fromId(request.category()).isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown category");
      }
      item.setCategory(request.category().trim());
    }
    if (request.name() != null) {
      item.setName(request.name().trim());
    }
    if (request.description() != null) {
      item.setDescription(request.description());
    }
    if (request.priceFrom() != null) {
      item.setPriceFrom(request.priceFrom());
    }
    if (request.priceTo() != null) {
      item.setPriceTo(request.priceTo());
    }
    if (request.durationMin() != null) {
      item.setDurationMin(request.durationMin());
    }
    if (request.active() != null) {
      item.setActive(request.active());
    }
    if (request.sortOrder() != null) {
      item.setSortOrder(request.sortOrder());
    }

    return toResponse(repository.save(item));
  }

  private ServiceCatalogItemResponse toResponse(ServiceCatalogItem item) {
    return new ServiceCatalogItemResponse(
        item.getId(), item.getCategory(), item.getCode(), item.getName(), item.getDescription(),
        item.getPriceFrom(), item.getPriceTo(), item.getDurationMin(), item.isActive(), item.getSortOrder(),
        item.getCreatedAt(), item.getUpdatedAt());
  }
}
