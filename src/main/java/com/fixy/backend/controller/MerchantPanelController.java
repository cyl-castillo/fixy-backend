package com.fixy.backend.controller;

import com.fixy.backend.dto.BusinessInquiryOwnerAnswerRequest;
import com.fixy.backend.dto.BusinessInquiryOwnerAnswerResponse;
import com.fixy.backend.dto.MerchantOfferRenewRequest;
import com.fixy.backend.dto.MerchantOfferSummary;
import com.fixy.backend.dto.MerchantPanelResponse;
import com.fixy.backend.model.Business;
import com.fixy.backend.service.BusinessInquiryService;
import com.fixy.backend.service.MerchantPanelService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
  private final BusinessInquiryService businessInquiryService;

  public MerchantPanelController(MerchantPanelService merchantPanelService, BusinessInquiryService businessInquiryService) {
    this.merchantPanelService = merchantPanelService;
    this.businessInquiryService = businessInquiryService;
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

  /**
   * El dueño contesta SÍ/NO a una consulta escalada del motor de respuesta
   * (Fase 2). Mismo criterio de token-en-el-path que el resto del panel;
   * 404 opaco si la consulta no es de este comercio, 409 si ya la
   * contestaron (ver {@code BusinessInquiryService.answerAsOwner}).
   */
  @PostMapping("/{token}/inquiries/{inquiryId}/answer")
  public BusinessInquiryOwnerAnswerResponse answerInquiry(
      @PathVariable String token,
      @PathVariable Long inquiryId,
      @RequestBody BusinessInquiryOwnerAnswerRequest request,
      HttpServletRequest httpRequest
  ) {
    return businessInquiryService.answerAsOwner(
        httpRequest.getRemoteAddr(), token, inquiryId, request.answer(), request.priceFrom(), request.note());
  }

  /**
   * Vincula la cuenta de Google al comercio (Google Sign-In del panel, Fase
   * 1): el token del link mágico prueba posesión; después el dueño puede
   * entrar desde cualquier teléfono vía POST /api/public/auth/google-business.
   * Mismo criterio de token-en-el-path que el resto de este controller.
   */
  @PostMapping("/{token}/link-google")
  public LinkGoogleResponse linkGoogle(
      @PathVariable String token,
      @Valid @RequestBody LinkGoogleRequest request,
      HttpServletRequest httpRequest
  ) {
    Business linked = merchantPanelService.linkGoogle(httpRequest.getRemoteAddr(), token, request.credential());
    return new LinkGoogleResponse(linked.getGoogleEmail());
  }

  public record LinkGoogleRequest(@NotNull String credential) {
  }

  public record LinkGoogleResponse(String googleEmail) {
  }
}
