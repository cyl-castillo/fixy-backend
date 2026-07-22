package com.fixy.backend.service;

import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.ProviderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Google Sign-In para PROVEEDORES (pedido de Carlos 2026-07-22): hoy el
 * panel solo se abre con el link mágico /p/{id}/{token} — si el proveedor
 * pierde el link, queda afuera hasta que ops se lo reenvía. Flujo nuevo:
 *
 * 1. VINCULAR (una vez): con el panel abierto vía link mágico (posesión
 *    probada), el proveedor toca "entrar con Google" en su perfil →
 *    {@link #link}. La cuenta queda atada por el sub del ID token.
 * 2. ENTRAR (siempre): en /panel toca el botón de Google → {@link #login}
 *    devuelve providerId + accessToken (las mismas credenciales del link
 *    mágico, sin rotarlas: los links ya compartidos siguen valiendo).
 *
 * Reusa el verificador de clientes ({@link GoogleIdTokenVerifierService}) —
 * misma validación de firma/audience/expiración.
 */
@Service
public class ProviderGoogleAuthService {

  private final GoogleIdTokenVerifierService verifier;
  private final ProviderRepository providerRepository;
  private final ProviderSelfService selfService;

  public ProviderGoogleAuthService(
      GoogleIdTokenVerifierService verifier,
      ProviderRepository providerRepository,
      ProviderSelfService selfService
  ) {
    this.verifier = verifier;
    this.providerRepository = providerRepository;
    this.selfService = selfService;
  }

  /** Vincula la cuenta Google al proveedor (autenticado por token de panel en el controller). Idempotente para la misma cuenta. */
  public Provider link(Provider provider, String credential) {
    GoogleIdTokenVerifierService.GoogleIdentity identity = verifyOrThrow(credential);
    Provider existing = providerRepository.findByGoogleSub(identity.sub()).orElse(null);
    if (existing != null && !existing.getId().equals(provider.getId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "esa cuenta de Google ya está vinculada a otro proveedor");
    }
    provider.setGoogleSub(identity.sub());
    provider.setGoogleEmail(identity.email());
    return providerRepository.save(provider);
  }

  /** Login por Google: devuelve el proveedor vinculado con accessToken garantizado (sin rotar el existente). */
  public Provider login(String credential) {
    GoogleIdTokenVerifierService.GoogleIdentity identity = verifyOrThrow(credential);
    Provider provider = providerRepository.findByGoogleSub(identity.sub())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "esta cuenta de Google no está vinculada a ningún proveedor"));
    return selfService.ensureAccessToken(provider.getId());
  }

  private GoogleIdTokenVerifierService.GoogleIdentity verifyOrThrow(String credential) {
    if (credential == null || credential.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "credential requerido");
    }
    return verifier.verify(credential)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "credential de Google inválido"));
  }
}
