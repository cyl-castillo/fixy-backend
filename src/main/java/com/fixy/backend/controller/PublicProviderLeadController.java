package com.fixy.backend.controller;

import com.fixy.backend.dto.ProviderLeadCreateRequest;
import com.fixy.backend.dto.ProviderLeadResponse;
import com.fixy.backend.service.ProviderLeadService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/providers")
public class PublicProviderLeadController {

  private final ProviderLeadService providerLeadService;

  public PublicProviderLeadController(ProviderLeadService providerLeadService) {
    this.providerLeadService = providerLeadService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProviderLeadResponse create(@Valid @RequestBody ProviderLeadCreateRequest request) {
    return providerLeadService.create(request);
  }
}
