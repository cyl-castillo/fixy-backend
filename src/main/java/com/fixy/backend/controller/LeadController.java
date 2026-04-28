package com.fixy.backend.controller;

import com.fixy.backend.dto.DiscoveredProviderCreateRequest;
import com.fixy.backend.dto.DiscoveredProviderLinkResponse;
import com.fixy.backend.dto.LeadCreateRequest;
import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.LeadUpdateRequest;
import com.fixy.backend.service.LeadService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leads")
public class LeadController {

  private final LeadService leadService;

  public LeadController(LeadService leadService) {
    this.leadService = leadService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LeadResponse create(@Valid @RequestBody LeadCreateRequest request) {
    return leadService.create(request);
  }

  @GetMapping
  public List<LeadResponse> list(@RequestParam(required = false) String status) {
    return leadService.list(status);
  }

  @PatchMapping("/{id}")
  public LeadResponse update(@PathVariable Long id, @RequestBody LeadUpdateRequest request) {
    return leadService.update(id, request);
  }

  @PostMapping("/{id}/discovered-provider")
  @ResponseStatus(HttpStatus.CREATED)
  public DiscoveredProviderLinkResponse createDiscoveredProvider(
      @PathVariable Long id,
      @Valid @RequestBody DiscoveredProviderCreateRequest request
  ) {
    return leadService.createDiscoveredProvider(id, request);
  }
}
