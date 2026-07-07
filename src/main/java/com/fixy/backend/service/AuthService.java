package com.fixy.backend.service;

import com.fixy.backend.model.AppUser;
import com.fixy.backend.repository.AppUserRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Orquesta el login con Google y la sesión propia: verifica el ID token,
 * hace upsert del {@link AppUser} por googleSub, emite el session token, y
 * resuelve el userId a partir del header Authorization en los endpoints de
 * usuario. No se agregan filter chains de Spring Security nuevas — la
 * seguridad de /api/public/me/** es este Bearer verificado a mano, igual
 * que el accessToken de leads.
 */
@Service
public class AuthService {

  private final GoogleIdTokenVerifierService googleVerifier;
  private final SessionTokenService sessionTokenService;
  private final AppUserRepository appUserRepository;

  public AuthService(
      GoogleIdTokenVerifierService googleVerifier,
      SessionTokenService sessionTokenService,
      AppUserRepository appUserRepository
  ) {
    this.googleVerifier = googleVerifier;
    this.sessionTokenService = sessionTokenService;
    this.appUserRepository = appUserRepository;
  }

  public boolean isEnabled() {
    return googleVerifier.isEnabled() && sessionTokenService.isEnabled();
  }

  /** Verifica el credential de Google, hace upsert del AppUser y emite el
   * session token propio. 503 si el feature no está configurado, 401 si el
   * ID token es inválido/expirado. */
  public LoginResult loginWithGoogle(String credential) {
    requireEnabled();
    GoogleIdTokenVerifierService.GoogleIdentity identity = googleVerifier.verify(credential)
        .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "invalid or expired google credential"));

    AppUser user = appUserRepository.findByGoogleSub(identity.sub())
        .orElseGet(AppUser::new);
    user.setGoogleSub(identity.sub());
    user.setEmail(identity.email());
    user.setName(identity.name());
    user.setPictureUrl(identity.pictureUrl());
    user.setLastLoginAt(OffsetDateTime.now());
    user = appUserRepository.save(user);

    String sessionToken = sessionTokenService.issue(user.getId());
    return new LoginResult(sessionToken, user);
  }

  /** Resuelve el userId a partir del header Authorization: Bearer <token>.
   * 503 si el feature no está configurado, 401 si falta el header o el
   * token es inválido/expirado/manipulado. */
  public Long requireUser(String authorizationHeader) {
    requireEnabled();
    String token = extractBearerToken(authorizationHeader)
        .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "missing bearer token"));
    return sessionTokenService.validate(token)
        .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "invalid or expired session token"));
  }

  private void requireEnabled() {
    if (!isEnabled()) {
      throw new ResponseStatusException(SERVICE_UNAVAILABLE, "google auth not configured");
    }
  }

  private Optional<String> extractBearerToken(String authorizationHeader) {
    if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
      return Optional.empty();
    }
    String token = authorizationHeader.substring("Bearer ".length()).trim();
    return token.isBlank() ? Optional.empty() : Optional.of(token);
  }

  public record LoginResult(String sessionToken, AppUser user) {
  }
}
