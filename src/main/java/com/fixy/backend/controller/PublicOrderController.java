package com.fixy.backend.controller;

import com.fixy.backend.dto.OrderCreateRequest;
import com.fixy.backend.dto.OrderCreateResponse;
import com.fixy.backend.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Pedido estructurado con precio cerrado (contrato §3). permitAll. */
@RestController
@RequestMapping("/api/public/orders")
public class PublicOrderController {

  private final OrderService orderService;

  public PublicOrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OrderCreateResponse create(@Valid @RequestBody OrderCreateRequest request, HttpServletRequest httpRequest) {
    return orderService.create(request, httpRequest.getRemoteAddr());
  }
}
