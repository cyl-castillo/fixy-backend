package com.fixy.backend.controller;

import com.fixy.backend.dto.WellKnownAgentResponse;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.service.ServiceCatalogService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Descripción legible por máquinas de Fixy (contrato §8) — el "robots.txt"
 * para asistentes de IA: qué es Fixy, qué cubre, y cómo llamarlo (MCP) o
 * consultarlo (catálogo REST) sin necesitar credenciales.
 */
@RestController
public class WellKnownAgentController {

  private final ServiceCatalogService serviceCatalogService;
  private final String publicAppBaseUrl;
  private final String publicApiBaseUrl;
  private final String contactWhatsapp;

  public WellKnownAgentController(
      ServiceCatalogService serviceCatalogService,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl,
      @Value("${fixy.public-api-base-url:https://api.fixy.com.uy}") String publicApiBaseUrl,
      @Value("${fixy.contact.whatsapp:59893551242}") String contactWhatsapp
  ) {
    this.serviceCatalogService = serviceCatalogService;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
    this.publicApiBaseUrl = publicApiBaseUrl.replaceAll("/+$", "");
    this.contactWhatsapp = contactWhatsapp;
  }

  @GetMapping("/.well-known/fixy-agent.json")
  public WellKnownAgentResponse describe() {
    return new WellKnownAgentResponse(
        "fixy",
        "Fixy conecta vecinos de Ciudad de la Costa (Canelones, Uruguay) con técnicos y proveedores de servicios del barrio — plomería, aires acondicionados, barométrica y más.",
        publicAppBaseUrl,
        "Ciudad de la Costa, Canelones, Uruguay",
        List.copyOf(serviceCatalogService.activeCategories()),
        List.copyOf(CoverageZone.LABELS),
        publicApiBaseUrl + "/api/public/mcp",
        publicApiBaseUrl + "/api/public/catalog/services",
        "https://wa.me/" + contactWhatsapp
    );
  }
}
