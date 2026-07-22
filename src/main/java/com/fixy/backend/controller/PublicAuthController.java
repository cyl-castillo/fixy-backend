package com.fixy.backend.controller;

import com.fixy.backend.dto.GoogleLoginRequest;
import com.fixy.backend.dto.GoogleLoginResponse;
import com.fixy.backend.model.AppUser;
import com.fixy.backend.model.Provider;
import com.fixy.backend.service.AuthService;
import com.fixy.backend.service.ProviderGoogleAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google Sign-In opcional y post-valor. El chat anónimo por accessToken
 * sigue siendo el flujo principal; este endpoint solo agrega identidad
 * persistente cross-device para quien elige loguearse.
 */
@RestController
@RequestMapping("/api/public/auth")
public class PublicAuthController {

  private final AuthService authService;
  private final ProviderGoogleAuthService providerGoogleAuthService;

  public PublicAuthController(AuthService authService, ProviderGoogleAuthService providerGoogleAuthService) {
    this.authService = authService;
    this.providerGoogleAuthService = providerGoogleAuthService;
  }

  @PostMapping("/google")
  @ResponseStatus(HttpStatus.OK)
  public GoogleLoginResponse loginWithGoogle(@RequestBody GoogleLoginRequest request) {
    AuthService.LoginResult result = authService.loginWithGoogle(request.credential());
    AppUser user = result.user();
    return new GoogleLoginResponse(
        result.sessionToken(),
        new GoogleLoginResponse.AppUserSummary(user.getName(), user.getEmail(), user.getPictureUrl())
    );
  }

  /**
   * Login con Google del PROVEEDOR (ver ProviderGoogleAuthService): devuelve
   * las credenciales del panel (providerId + accessToken, las mismas del
   * link mágico) si la cuenta está vinculada. 404 si no lo está — el
   * frontend guía a vincular desde el panel abierto por link.
   */
  @PostMapping("/google-provider")
  @ResponseStatus(HttpStatus.OK)
  public ProviderGoogleLoginResponse providerLoginWithGoogle(@RequestBody GoogleLoginRequest request) {
    Provider provider = providerGoogleAuthService.login(request.credential());
    return new ProviderGoogleLoginResponse(provider.getId(), provider.getAccessToken(), provider.getName());
  }

  public record ProviderGoogleLoginResponse(Long providerId, String accessToken, String name) {
  }
}
