package com.fixy.backend.controller;

import com.fixy.backend.dto.ServiceCatalogItemCreateRequest;
import com.fixy.backend.dto.ServiceCatalogItemResponse;
import com.fixy.backend.dto.ServiceCatalogItemUpdateRequest;
import com.fixy.backend.service.ServiceCatalogService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD admin del catálogo de servicios (contrato §2) — mismo httpBasic + rol
 * OPS que {@code /api/providers/**}/{@code /api/offers/**}. Sin DELETE: se
 * desactiva (PATCH active=false), mismo criterio de soft-delete que el
 * resto del repo.
 */
@RestController
@RequestMapping("/api/services")
public class ServiceCatalogController {

  private final ServiceCatalogService serviceCatalogService;

  public ServiceCatalogController(ServiceCatalogService serviceCatalogService) {
    this.serviceCatalogService = serviceCatalogService;
  }

  @GetMapping
  public List<ServiceCatalogItemResponse> list() {
    return serviceCatalogService.adminList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ServiceCatalogItemResponse create(@Valid @RequestBody ServiceCatalogItemCreateRequest request) {
    return serviceCatalogService.create(request);
  }

  @PatchMapping("/{id}")
  public ServiceCatalogItemResponse update(@PathVariable Long id, @RequestBody ServiceCatalogItemUpdateRequest request) {
    return serviceCatalogService.update(id, request);
  }
}
