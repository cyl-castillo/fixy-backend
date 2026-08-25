package com.fixy.backend.controller;

import com.fixy.backend.dto.MerchantOfferRenewRequest;
import com.fixy.backend.dto.MerchantOfferSummary;
import com.fixy.backend.dto.MerchantPanelResponse;
import com.fixy.backend.service.MerchantPanelService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel self-service del comercio (Fase 5): sin login, el token EN EL PATH
 * es la única credencial — mismo criterio "permitAll bajo /api/public/**"
 * que el resto de las rutas públicas ({@code SecurityConfig}), sin cambios
 * adicionales de seguridad necesarios acá. Token inválido → 404 opaco
 * SIEMPRE (ver {@code MerchantPanelService}).
 */
@RestController
@RequestMapping("/api/public/merchant")
public class MerchantPanelController {

  private final MerchantPanelService merchantPanelService;

  public MerchantPanelController(MerchantPanelService merchantPanelService) {
    this.merchantPanelService = merchantPanelService;
  }

  @GetMapping("/{token}")
  public MerchantPanelResponse getPanel(@PathVariable String token, HttpServletRequest httpRequest) {
    return merchantPanelService.getPanel(httpRequest.getRemoteAddr(), token);
  }

  @PostMapping("/{token}/offers/{offerId}/renew")
  public MerchantOfferSummary renew(
      @PathVariable String token,
      @PathVariable Long offerId,
      @RequestBody MerchantOfferRenewRequest request,
      HttpServletRequest httpRequest
  ) {
    return merchantPanelService.renew(httpRequest.getRemoteAddr(), token, offerId, request.weeks());
  }

  @PostMapping("/{token}/offers/{offerId}/pause")
  public MerchantOfferSummary pause(
      @PathVariable String token,
      @PathVariable Long offerId,
      HttpServletRequest httpRequest
  ) {
    return merchantPanelService.pause(httpRequest.getRemoteAddr(), token, offerId);
  }
}
