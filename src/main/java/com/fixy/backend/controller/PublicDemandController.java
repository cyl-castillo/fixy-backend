package com.fixy.backend.controller;

import com.fixy.backend.dto.PublicDemandResponse;
import com.fixy.backend.service.PublicDemandService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/public/demand} — demanda abierta por oficio para el hero de
 * {@code /sumate} (ver {@link PublicDemandService} y el dato que lo motivó).
 * {@code permitAll} vía {@code /api/public/**} en {@code SecurityConfig}, sin
 * cambios de seguridad adicionales: la respuesta son conteos agregados, sin
 * ningún dato del vecino que pidió.
 */
@RestController
@RequestMapping("/api/public/demand")
public class PublicDemandController {

  private final PublicDemandService publicDemandService;

  public PublicDemandController(PublicDemandService publicDemandService) {
    this.publicDemandService = publicDemandService;
  }

  @GetMapping
  public PublicDemandResponse demand() {
    return publicDemandService.get();
  }
}
