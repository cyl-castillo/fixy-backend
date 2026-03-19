package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadCreateRequest;
import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.service.LeadService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicLeadController {

  private final LeadService leadService;

  public PublicLeadController(LeadService leadService) {
    this.leadService = leadService;
  }

  @PostMapping("/leads")
  @ResponseStatus(HttpStatus.CREATED)
  public LeadResponse create(@Valid @RequestBody LeadCreateRequest request) {
    return leadService.create(request);
  }
}
