package com.fixy.backend.controller;

import com.fixy.backend.dto.BusinessCatalogItemCreateRequest;
import com.fixy.backend.dto.BusinessCatalogItemResponse;
import com.fixy.backend.dto.BusinessCatalogItemUpdateRequest;
import com.fixy.backend.dto.BusinessCreateRequest;
import com.fixy.backend.dto.BusinessEventResponse;
import com.fixy.backend.dto.BusinessHourRequest;
import com.fixy.backend.dto.BusinessHourResponse;
import com.fixy.backend.dto.BusinessPanelLinkResponse;
import com.fixy.backend.dto.BusinessResponse;
import com.fixy.backend.dto.BusinessUpdateRequest;
import com.fixy.backend.service.BusinessCatalogItemService;
import com.fixy.backend.service.BusinessHourService;
import com.fixy.backend.service.BusinessService;
import com.fixy.backend.service.BusinessTimelineService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** CRUD admin de comercios de ofertas — mismo httpBasic + rol OPS que /api/providers/**. */
@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

  private final BusinessService businessService;
  private final BusinessCatalogItemService businessCatalogItemService;
  private final BusinessHourService businessHourService;
  private final BusinessTimelineService businessTimelineService;

  public BusinessController(
      BusinessService businessService,
      BusinessCatalogItemService businessCatalogItemService,
      BusinessHourService businessHourService,
      BusinessTimelineService businessTimelineService
  ) {
    this.businessService = businessService;
    this.businessCatalogItemService = businessCatalogItemService;
    this.businessHourService = businessHourService;
    this.businessTimelineService = businessTimelineService;
  }

  @GetMapping
  public List<BusinessResponse> list() {
    return businessService.list();
  }

  @GetMapping("/{id}")
  public BusinessResponse get(@PathVariable Long id) {
    return businessService.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BusinessResponse create(@Valid @RequestBody BusinessCreateRequest request) {
    return businessService.create(request);
  }

  @PatchMapping("/{id}")
  public BusinessResponse update(@PathVariable Long id, @RequestBody BusinessUpdateRequest request) {
    return businessService.update(id, request);
  }

  /**
   * Link del panel self-service del dueño (Fase 5): genera el token si no
   * existe, devuelve el mismo si ya lo tenía — nunca regenera solo (ver
   * {@code BusinessService.ensurePanelLink}).
   */
  @PostMapping("/{id}/panel-link")
  public BusinessPanelLinkResponse panelLink(@PathVariable Long id) {
    return businessService.ensurePanelLink(id);
  }

  // --- Fase 1 de la ficha (V24): catálogo estructurado ---

  @GetMapping("/{id}/catalog")
  public List<BusinessCatalogItemResponse> listCatalog(@PathVariable Long id) {
    return businessCatalogItemService.list(id);
  }

  @PostMapping("/{id}/catalog")
  @ResponseStatus(HttpStatus.CREATED)
  public BusinessCatalogItemResponse createCatalogItem(
      @PathVariable Long id,
      @Valid @RequestBody BusinessCatalogItemCreateRequest request
  ) {
    return businessCatalogItemService.create(id, request);
  }

  @PutMapping("/{id}/catalog/{itemId}")
  public BusinessCatalogItemResponse updateCatalogItem(
      @PathVariable Long id,
      @PathVariable Long itemId,
      @Valid @RequestBody BusinessCatalogItemUpdateRequest request
  ) {
    return businessCatalogItemService.update(id, itemId, request);
  }

  /** Soft delete (active=false), idempotente. */
  @DeleteMapping("/{id}/catalog/{itemId}")
  public void deleteCatalogItem(@PathVariable Long id, @PathVariable Long itemId) {
    businessCatalogItemService.delete(id, itemId);
  }

  // --- Fase 1 de la ficha (V24): horarios ---

  @GetMapping("/{id}/hours")
  public List<BusinessHourResponse> listHours(@PathVariable Long id) {
    return businessHourService.list(id);
  }

  /** Reemplaza el set completo de franjas horarias del comercio. */
  @PutMapping("/{id}/hours")
  public List<BusinessHourResponse> replaceHours(
      @PathVariable Long id,
      @Valid @RequestBody List<BusinessHourRequest> hours
  ) {
    return businessHourService.replace(id, hours);
  }

  // --- Fase 1 de la ficha (V24): timeline ---

  @GetMapping("/{id}/events")
  public List<BusinessEventResponse> listEvents(@PathVariable Long id) {
    return businessTimelineService.listForBusiness(id);
  }
}
