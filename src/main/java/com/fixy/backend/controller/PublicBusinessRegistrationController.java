package com.fixy.backend.controller;

import com.fixy.backend.dto.PublicBusinessRegistrationRequest;
import com.fixy.backend.dto.PublicBusinessRegistrationResponse;
import com.fixy.backend.model.Business;
import com.fixy.backend.service.BusinessRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Autoregistro público de comercios (ver {@link BusinessRegistrationService}):
 * Google ancla la identidad, el comercio nace {@code ACTIVE} (las ofertas
 * siguen moderadas) y recibe su panelToken de una. 200 en los dos casos de
 * éxito (alta nueva o login implícito por sub ya vinculado) — el contrato no
 * distingue con el código de estado, distingue con {@code alreadyExisted}.
 */
@RestController
@RequestMapping("/api/public/businesses")
public class PublicBusinessRegistrationController {

  private final BusinessRegistrationService businessRegistrationService;

  public PublicBusinessRegistrationController(BusinessRegistrationService businessRegistrationService) {
    this.businessRegistrationService = businessRegistrationService;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.OK)
  public PublicBusinessRegistrationResponse register(
      @Valid @RequestBody PublicBusinessRegistrationRequest request,
      HttpServletRequest httpRequest
  ) {
    BusinessRegistrationService.BusinessRegistrationResult result = businessRegistrationService.register(
        request.credential(),
        request.name(),
        request.whatsappNumber(),
        request.category(),
        request.zone(),
        request.address(),
        httpRequest.getRemoteAddr()
    );
    Business business = result.business();
    return new PublicBusinessRegistrationResponse(
        business.getId(), business.getName(), business.getPanelToken(), result.alreadyExisted()
    );
  }
}
