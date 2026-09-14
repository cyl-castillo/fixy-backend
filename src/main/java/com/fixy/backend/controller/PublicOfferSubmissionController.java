package com.fixy.backend.controller;

import com.fixy.backend.dto.PublicOfferSubmissionRequest;
import com.fixy.backend.dto.PublicOfferSubmissionResponse;
import com.fixy.backend.service.PublicOfferSubmissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Alta pública de ofertas (fase 2 del roadmap "ofertas protagonistas"): el
 * comerciante la carga solo desde su celular, sin pasar por el mostrador ni
 * el admin de ops. Mismo patrón que {@code PublicOfferController} — vive
 * bajo {@code /api/public/**} ({@code permitAll} en {@code SecurityConfig},
 * sin cambios adicionales de seguridad necesarios).
 *
 * <p>La oferta creada acá nace SIEMPRE en {@code DRAFT} — el pipeline de
 * aprobación no cambia, ver {@link PublicOfferSubmissionService}.
 */
@RestController
@RequestMapping("/api/public/offer-submissions")
public class PublicOfferSubmissionController {

  private final PublicOfferSubmissionService publicOfferSubmissionService;
  private final boolean offersEnabled;

  public PublicOfferSubmissionController(
      PublicOfferSubmissionService publicOfferSubmissionService,
      // Congelamiento de ofertas (Refundación fase 1, contrato §6).
      @Value("${fixy.offers.enabled:false}") boolean offersEnabled
  ) {
    this.publicOfferSubmissionService = publicOfferSubmissionService;
    this.offersEnabled = offersEnabled;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PublicOfferSubmissionResponse create(
      @RequestBody PublicOfferSubmissionRequest request,
      HttpServletRequest httpRequest
  ) {
    if (!offersEnabled) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Las ofertas están pausadas por ahora.");
    }
    return publicOfferSubmissionService.create(request, httpRequest.getRemoteAddr());
  }
}
