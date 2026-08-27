package com.fixy.backend.service;

import com.fixy.backend.model.Business;
import com.fixy.backend.repository.BusinessRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Google Sign-In del DUEÑO del comercio (Fase 1, pedido de Carlos
 * 2026-08-27) — espejo de {@link ProviderGoogleAuthService} para
 * proveedores: hoy el panel solo se abre con el link mágico
 * /mi-comercio/{token} — si el dueño lo pierde, queda afuera hasta que ops
 * se lo reenvía. Mismo flujo de dos pasos:
 *
 * 1. VINCULAR (una vez): con el panel abierto vía link mágico (posesión
 *    probada), el dueño toca "entrar con Google" en su panel → {@link
 *    #link}. La cuenta queda atada por el sub del ID token; el email
 *    también se guarda (primera vez que Fixy conoce el email real del
 *    dueño del comercio).
 * 2. ENTRAR (siempre): en /mi-comercio toca el botón de Google → {@link
 *    #login} devuelve el panelToken existente vía {@link
 *    BusinessService#ensurePanel}, SIN rotarlo — los links ya compartidos
 *    por WhatsApp siguen valiendo.
 *
 * Reusa el verificador de proveedores/clientes ({@link
 * GoogleIdTokenVerifierService}) — misma validación de firma/audience/
 * expiración.
 *
 * <p>Diferencia deliberada con el precedente de proveedor: acá hay un check
 * EXPLÍCITO de {@code verifier.isEnabled()} → 503 (mismo patrón que {@link
 * AuthService#loginWithGoogle}), en vez de dejar que {@code verify()}
 * devuelva vacío y el caller lo lea como 401 — el contrato de esta feature
 * pide 503 distinguible de "credential inválido" cuando falta
 * GOOGLE_CLIENT_ID.
 */
@Service
public class BusinessGoogleAuthService {

  private final GoogleIdTokenVerifierService verifier;
  private final BusinessRepository businessRepository;
  private final BusinessService businessService;

  public BusinessGoogleAuthService(
      GoogleIdTokenVerifierService verifier,
      BusinessRepository businessRepository,
      BusinessService businessService
  ) {
    this.verifier = verifier;
    this.businessRepository = businessRepository;
    this.businessService = businessService;
  }

  /**
   * Vincula la cuenta Google al comercio (autenticado por token de panel en
   * el controller). Re-vincular el MISMO comercio con OTRA cuenta está
   * permitido — la posesión del link del panel manda; vincular con el mismo
   * sub es idempotente. 409 solo si el sub ya pertenece a OTRO comercio.
   */
  public Business link(Business business, String credential) {
    GoogleIdTokenVerifierService.GoogleIdentity identity = verifyOrThrow(credential);
    Business existing = businessRepository.findByGoogleSub(identity.sub()).orElse(null);
    if (existing != null && !existing.getId().equals(business.getId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "esa cuenta de Google ya está vinculada a otro comercio");
    }
    business.setGoogleSub(identity.sub());
    business.setGoogleEmail(identity.email());
    return businessRepository.save(business);
  }

  /** Login por Google: devuelve el comercio vinculado con panelToken garantizado (sin rotar el existente). */
  public Business login(String credential) {
    GoogleIdTokenVerifierService.GoogleIdentity identity = verifyOrThrow(credential);
    Business business = businessRepository.findByGoogleSub(identity.sub())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "esta cuenta de Google no está vinculada a ningún comercio"));
    return businessService.ensurePanel(business);
  }

  private GoogleIdTokenVerifierService.GoogleIdentity verifyOrThrow(String credential) {
    if (!verifier.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "google auth not configured");
    }
    if (credential == null || credential.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "credential requerido");
    }
    return verifier.verify(credential)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "credential de Google inválido"));
  }
}
