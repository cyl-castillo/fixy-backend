package com.fixy.backend.controller;

import com.fixy.backend.dto.ProviderAccessTokenResponse;
import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.dto.ProviderCreateRequest;
import com.fixy.backend.dto.ProviderResponse;
import com.fixy.backend.dto.ProviderUpdateRequest;
import com.fixy.backend.model.Provider;
import com.fixy.backend.service.ProviderCatalogService;
import com.fixy.backend.service.ProviderSelfService;
import org.springframework.beans.factory.annotation.Value;
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

@RestController
@RequestMapping("/api/providers")
public class ProviderCatalogController {

  private final ProviderCatalogService providerCatalogService;
  private final ProviderSelfService selfService;
  private final String publicAppBaseUrl;

  public ProviderCatalogController(
      ProviderCatalogService providerCatalogService,
      ProviderSelfService selfService,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl
  ) {
    this.providerCatalogService = providerCatalogService;
    this.selfService = selfService;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
  }

  @GetMapping("/catalog")
  public List<ProviderCatalogItem> listCatalog() {
    return providerCatalogService.list();
  }

  @GetMapping
  public List<ProviderResponse> list() {
    return providerCatalogService.listDetailed();
  }

  @GetMapping("/{id}")
  public ProviderResponse get(@PathVariable Long id) {
    return providerCatalogService.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProviderResponse create(@Valid @RequestBody ProviderCreateRequest request) {
    return providerCatalogService.create(request);
  }

  @PatchMapping("/{id}")
  public ProviderResponse update(@PathVariable Long id, @RequestBody ProviderUpdateRequest request) {
    return providerCatalogService.update(id, request);
  }

  /** Genera (o regenera) el token de acceso del proveedor y devuelve la
   *  URL para compartir vía WhatsApp manualmente. */
  @PostMapping("/{id}/access-token")
  public ProviderAccessTokenResponse rotateAccessToken(@PathVariable Long id) {
    Provider provider = selfService.regenerateAccessToken(id);
    String url = "%s/p/%d/%s".formatted(publicAppBaseUrl, provider.getId(), provider.getAccessToken());
    return new ProviderAccessTokenResponse(provider.getId(), provider.getName(), provider.getAccessToken(), url);
  }
}
