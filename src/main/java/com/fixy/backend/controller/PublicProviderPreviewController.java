package com.fixy.backend.controller;

import com.fixy.backend.dto.ProviderPublicPreview;
import com.fixy.backend.service.ProviderCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/providers")
public class PublicProviderPreviewController {

  private final ProviderCatalogService providerCatalogService;

  public PublicProviderPreviewController(ProviderCatalogService providerCatalogService) {
    this.providerCatalogService = providerCatalogService;
  }

  @GetMapping("/preview")
  public ProviderPublicPreview preview(
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "zone", required = false) String zone,
      @RequestParam(value = "limit", required = false, defaultValue = "3") int limit
  ) {
    return providerCatalogService.publicPreview(category, zone, limit);
  }
}
