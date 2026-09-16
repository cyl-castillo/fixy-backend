package com.fixy.backend.controller;

import com.fixy.backend.dto.PriceChangeActionResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.PriceChangeStatus;
import com.fixy.backend.service.PriceChangeService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tier 1 (contrato §B.1): aceptación/rechazo del cliente sobre la
 * propuesta de precio nuevo del proveedor — autenticado por el accessToken
 * del LEAD, mismo patrón que {@link PublicLeadClosingController} (el
 * proveedor no puede aceptar/rechazar en nombre del cliente).
 */
@RestController
@RequestMapping("/api/public/leads/{leadId}/price-change")
public class PublicLeadPriceChangeController {

  private final PriceChangeService priceChangeService;

  public PublicLeadPriceChangeController(PriceChangeService priceChangeService) {
    this.priceChangeService = priceChangeService;
  }

  @PostMapping("/accept")
  public PriceChangeActionResponse accept(
      @PathVariable Long leadId,
      @RequestParam("token") String token
  ) {
    Lead lead = priceChangeService.accept(leadId, token);
    return toResponse(lead);
  }

  @PostMapping("/reject")
  public PriceChangeActionResponse reject(
      @PathVariable Long leadId,
      @RequestParam("token") String token
  ) {
    Lead lead = priceChangeService.reject(leadId, token);
    return toResponse(lead);
  }

  private PriceChangeActionResponse toResponse(Lead lead) {
    return new PriceChangeActionResponse(
        lead.getId(),
        PriceChangeStatus.of(lead),
        lead.getAgreedAmount(),
        lead.getAgreedAt()
    );
  }
}
