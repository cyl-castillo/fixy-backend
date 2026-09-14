package com.fixy.backend.controller;

import com.fixy.backend.dto.ServiceCatalogGroupResponse;
import com.fixy.backend.service.ServiceCatalogService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo público de servicios con precio cerrado (contrato §2). permitAll
 * (ver SecurityConfig), cacheable 5 min — es un catálogo que cambia poco y
 * lo puede pegar cualquier visitante en el home nuevo.
 */
@RestController
@RequestMapping("/api/public/catalog")
public class PublicServiceCatalogController {

  private final ServiceCatalogService serviceCatalogService;

  public PublicServiceCatalogController(ServiceCatalogService serviceCatalogService) {
    this.serviceCatalogService = serviceCatalogService;
  }

  @GetMapping("/services")
  public ResponseEntity<List<ServiceCatalogGroupResponse>> services(
      @RequestParam(value = "category", required = false) String category
  ) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5)))
        .body(serviceCatalogService.publicCatalog(category));
  }
}
