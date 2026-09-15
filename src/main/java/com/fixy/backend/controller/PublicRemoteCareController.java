package com.fixy.backend.controller;

import com.fixy.backend.dto.OrderCreateResponse;
import com.fixy.backend.dto.RemoteCareOrderCreateRequest;
import com.fixy.backend.dto.RemoteCarePlanDetailResponse;
import com.fixy.backend.dto.RemoteCarePlanRequest;
import com.fixy.backend.dto.RemoteCarePlanRequestResponse;
import com.fixy.backend.model.RemoteCarePlan;
import com.fixy.backend.service.RemoteCareRequestService;
import com.fixy.backend.service.RemoteCareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Plan Casa a distancia (Refundación de Fixy, fase 2, contrato §B.3). permitAll. */
@RestController
@RequestMapping("/api/public/remote-care")
public class PublicRemoteCareController {

  private final RemoteCareRequestService remoteCareRequestService;
  private final RemoteCareService remoteCareService;

  public PublicRemoteCareController(
      RemoteCareRequestService remoteCareRequestService, RemoteCareService remoteCareService
  ) {
    this.remoteCareRequestService = remoteCareRequestService;
    this.remoteCareService = remoteCareService;
  }

  @PostMapping("/requests")
  @ResponseStatus(HttpStatus.CREATED)
  public RemoteCarePlanRequestResponse create(
      @RequestBody RemoteCarePlanRequest request, HttpServletRequest httpRequest
  ) {
    return remoteCareRequestService.create(request, httpRequest.getRemoteAddr());
  }

  @GetMapping("/plans/{id}")
  public RemoteCarePlanDetailResponse plan(
      @org.springframework.web.bind.annotation.PathVariable Long id,
      @RequestParam("token") String token
  ) {
    RemoteCarePlan plan = remoteCareService.authenticate(id, token);
    return remoteCareService.detail(plan);
  }

  @PostMapping("/plans/{id}/orders")
  @ResponseStatus(HttpStatus.CREATED)
  public OrderCreateResponse createOrder(
      @org.springframework.web.bind.annotation.PathVariable Long id,
      @RequestParam("token") String token,
      @Valid @RequestBody RemoteCareOrderCreateRequest request,
      HttpServletRequest httpRequest
  ) {
    return remoteCareService.createOrder(id, token, request, httpRequest.getRemoteAddr());
  }
}
