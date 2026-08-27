package com.fixy.backend.controller;

import com.fixy.backend.dto.RegistrationCatalogResponse;
import com.fixy.backend.service.RegistrationCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo único de rubros para la puerta de registro pública (Fase 1+2,
 * ver {@code RegistrationCatalogService}) — {@code permitAll} vía
 * {@code /api/public/**} en {@code SecurityConfig}, sin cambios de
 * seguridad adicionales.
 */
@RestController
@RequestMapping("/api/public/catalog")
public class PublicRegistrationCatalogController {

  private final RegistrationCatalogService registrationCatalogService;

  public PublicRegistrationCatalogController(RegistrationCatalogService registrationCatalogService) {
    this.registrationCatalogService = registrationCatalogService;
  }

  @GetMapping("/registration")
  public RegistrationCatalogResponse registration() {
    return registrationCatalogService.get();
  }
}
