package com.fixy.backend.controller;

import com.fixy.backend.dto.BusinessCreateRequest;
import com.fixy.backend.dto.BusinessResponse;
import com.fixy.backend.dto.BusinessUpdateRequest;
import com.fixy.backend.service.BusinessService;
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

/** CRUD admin de comercios de ofertas — mismo httpBasic + rol OPS que /api/providers/**. */
@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

  private final BusinessService businessService;

  public BusinessController(BusinessService businessService) {
    this.businessService = businessService;
  }

  @GetMapping
  public List<BusinessResponse> list() {
    return businessService.list();
  }

  @GetMapping("/{id}")
  public BusinessResponse get(@PathVariable Long id) {
    return businessService.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BusinessResponse create(@Valid @RequestBody BusinessCreateRequest request) {
    return businessService.create(request);
  }

  @PatchMapping("/{id}")
  public BusinessResponse update(@PathVariable Long id, @RequestBody BusinessUpdateRequest request) {
    return businessService.update(id, request);
  }
}
