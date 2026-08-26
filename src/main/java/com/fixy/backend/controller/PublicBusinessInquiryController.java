package com.fixy.backend.controller;

import com.fixy.backend.dto.BusinessInquiryCreateRequest;
import com.fixy.backend.dto.BusinessInquiryCreateResponse;
import com.fixy.backend.dto.BusinessInquiryPushEndpointUpdateRequest;
import com.fixy.backend.dto.BusinessInquiryVisitorResponse;
import com.fixy.backend.service.BusinessInquiryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Motor de respuesta sobre el catálogo de la ficha, con escalado al dueño
 * (Fase 2 del gap analysis 2026-08-25 §2). Mismo patrón {@code permitAll}
 * bajo {@code /api/public/**} que el resto de las rutas públicas (ver
 * {@code SecurityConfig}) — sin cambios adicionales de seguridad
 * necesarios acá.
 */
@RestController
public class PublicBusinessInquiryController {

  private final BusinessInquiryService businessInquiryService;

  public PublicBusinessInquiryController(BusinessInquiryService businessInquiryService) {
    this.businessInquiryService = businessInquiryService;
  }

  /**
   * "¿Tenés X?" — responde sola si el catálogo tiene confianza suficiente,
   * si no queda {@code ESCALATED} y el vecino recibe un {@code accessToken}
   * para volver a consultar el estado (ver {@link #get}).
   */
  @PostMapping("/api/public/businesses/{businessId}/inquiries")
  @ResponseStatus(HttpStatus.CREATED)
  public BusinessInquiryCreateResponse create(
      @PathVariable Long businessId,
      @RequestBody BusinessInquiryCreateRequest request,
      HttpServletRequest httpRequest
  ) {
    return businessInquiryService.create(businessId, request, httpRequest.getRemoteAddr());
  }

  /** 404 opaco si el token no matchea la consulta (patrón panel del comercio). */
  @GetMapping("/api/public/inquiries/{id}")
  public BusinessInquiryVisitorResponse get(@PathVariable Long id, @RequestParam String token) {
    return businessInquiryService.getForVisitor(id, token);
  }

  /**
   * Adjunta un {@code pushEndpoint} tardío (el vecino activó notificaciones
   * después de crear la consulta). 204; 404 opaco si el token no matchea;
   * 409 si la consulta ya no está {@code ESCALATED} (ver {@code
   * BusinessInquiryService.attachPushEndpoint}).
   */
  @PatchMapping("/api/public/inquiries/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void attachPush(
      @PathVariable Long id,
      @RequestParam String token,
      @RequestBody BusinessInquiryPushEndpointUpdateRequest request,
      HttpServletRequest httpRequest
  ) {
    businessInquiryService.attachPushEndpoint(id, token, httpRequest.getRemoteAddr(), request.pushEndpoint());
  }
}
