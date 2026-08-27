package com.fixy.backend.controller;

import com.fixy.backend.dto.BusinessCatalogItemCreateRequest;
import com.fixy.backend.dto.BusinessCatalogItemResponse;
import com.fixy.backend.dto.BusinessCatalogItemUpdateRequest;
import com.fixy.backend.dto.BusinessHourRequest;
import com.fixy.backend.dto.BusinessHourResponse;
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
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

  // --- Fase 2 del panel self-service: el dueño edita su propia ficha ---
  // Mismo criterio de token-en-el-path; MISMO shape de DTO que los
  // endpoints admin espejo bajo /api/businesses/{id}/... (BusinessController)
  // para que el frontend reuse los mismos tipos.

  @GetMapping("/{token}/catalog")
  public List<BusinessCatalogItemResponse> getCatalog(@PathVariable String token, HttpServletRequest httpRequest) {
    return merchantPanelService.getCatalog(httpRequest.getRemoteAddr(), token);
  }

  @PostMapping("/{token}/catalog")
  @ResponseStatus(HttpStatus.CREATED)
  public BusinessCatalogItemResponse createCatalogItem(
      @PathVariable String token,
      @Valid @RequestBody BusinessCatalogItemCreateRequest request,
      HttpServletRequest httpRequest
  ) {
    return merchantPanelService.createCatalogItem(httpRequest.getRemoteAddr(), token, request);
  }

  @PutMapping("/{token}/catalog/{itemId}")
  public BusinessCatalogItemResponse updateCatalogItem(
      @PathVariable String token,
      @PathVariable Long itemId,
      @Valid @RequestBody BusinessCatalogItemUpdateRequest request,
      HttpServletRequest httpRequest
  ) {
    return merchantPanelService.updateCatalogItem(httpRequest.getRemoteAddr(), token, itemId, request);
  }

  /** Soft delete (active=false), idempotente — igual que el admin. */
  @DeleteMapping("/{token}/catalog/{itemId}")
  public void deleteCatalogItem(@PathVariable String token, @PathVariable Long itemId, HttpServletRequest httpRequest) {
    merchantPanelService.deleteCatalogItem(httpRequest.getRemoteAddr(), token, itemId);
  }

  @GetMapping("/{token}/hours")
  public List<BusinessHourResponse> getHours(@PathVariable String token, HttpServletRequest httpRequest) {
    return merchantPanelService.getHours(httpRequest.getRemoteAddr(), token);
  }

  /** Reemplaza el set completo de franjas horarias — igual que el admin. */
  @PutMapping("/{token}/hours")
  public List<BusinessHourResponse> replaceHours(
      @PathVariable String token,
      @Valid @RequestBody List<BusinessHourRequest> hours,
      HttpServletRequest httpRequest
  ) {
    return merchantPanelService.replaceHours(httpRequest.getRemoteAddr(), token, hours);
  }

  /**
   * El dueño solo puede tocar {@code description} desde su panel —
   * categories/matching queda territorio admin, por eso este DTO NO tiene
   * esos campos (a diferencia de {@code BusinessUpdateRequest} del PATCH
   * admin). {@code null} borra la descripción.
   */
  @PatchMapping("/{token}/business")
  public UpdateDescriptionResponse updateDescription(
      @PathVariable String token,
      @Valid @RequestBody UpdateDescriptionRequest request,
      HttpServletRequest httpRequest
  ) {
    String description = merchantPanelService.updateDescription(httpRequest.getRemoteAddr(), token, request.description());
    return new UpdateDescriptionResponse(description);
  }

  public record UpdateDescriptionRequest(
      @Size(max = 500, message = "description must be at most 500 characters") String description
  ) {
  }

  public record UpdateDescriptionResponse(String description) {
  }
}
