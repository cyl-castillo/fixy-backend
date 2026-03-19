package com.fixy.backend.controller;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.service.ProviderCatalogService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/providers")
public class ProviderCatalogController {

  private final ProviderCatalogService providerCatalogService;

  public ProviderCatalogController(ProviderCatalogService providerCatalogService) {
    this.providerCatalogService = providerCatalogService;
  }

  @GetMapping
  public List<ProviderCatalogItem> list() {
    return providerCatalogService.list();
  }
}
