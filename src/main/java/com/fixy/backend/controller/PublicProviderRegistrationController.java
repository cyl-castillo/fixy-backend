package com.fixy.backend.controller;

import com.fixy.backend.model.Provider;
import com.fixy.backend.service.ProviderRegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Autoregistro público de proveedores (ver ProviderRegistrationService):
 * Google ancla la identidad, el proveedor nace INACTIVE (pendiente de
 * aprobación en el admin) y recibe sus credenciales de panel para entrar
 * ya mismo con el banner de "cuenta en revisión".
 */
@RestController
@RequestMapping("/api/public/providers")
public class PublicProviderRegistrationController {

  private final ProviderRegistrationService registrationService;

  public PublicProviderRegistrationController(ProviderRegistrationService registrationService) {
    this.registrationService = registrationService;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
    Provider provider = registrationService.register(
        request.credential(),
        request.name(),
        request.phone(),
        request.categories(),
        request.primaryZone(),
        request.coverageZones()
    );
    return new RegisterResponse(provider.getId(), provider.getAccessToken(), provider.getName());
  }

  public record RegisterRequest(
      @NotNull String credential,
      @NotNull String name,
      @NotNull String phone,
      @NotNull List<String> categories,
      @NotNull String primaryZone,
      String coverageZones
  ) {
  }

  public record RegisterResponse(Long providerId, String accessToken, String name) {
  }
}
