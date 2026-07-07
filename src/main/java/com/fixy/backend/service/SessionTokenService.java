package com.fixy.backend.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Emite y valida la sesión propia de Fixy (JWT HS256, sub=userId, exp 30
 * días) que el frontend manda como {@code Authorization: Bearer} en los
 * endpoints de usuario. Firmado con {@code fixy.auth.session-secret}; si no
 * está configurado, el servicio queda disabled (mismo patrón que
 * {@link MercadoPagoService} / {@link GoogleIdTokenVerifierService}) y el
 * caller (AuthService) debe devolver 503.
 */
@Service
public class SessionTokenService {

  private static final Logger log = LoggerFactory.getLogger(SessionTokenService.class);
  private static final Duration SESSION_TTL = Duration.ofDays(30);
  private static final String ISSUER = "fixy-backend";

  private final boolean enabled;
  private final MACSigner signer;
  private final JWSVerifier verifier;

  public SessionTokenService(@Value("${fixy.auth.session-secret:}") String sessionSecret) {
    this.enabled = sessionSecret != null && sessionSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
    MACSigner s = null;
    JWSVerifier v = null;
    if (enabled) {
      try {
        byte[] secretBytes = sessionSecret.getBytes(StandardCharsets.UTF_8);
        s = new MACSigner(secretBytes);
        v = new MACVerifier(secretBytes);
      } catch (JOSEException ex) {
        throw new IllegalStateException("no se pudo inicializar el firmante de sesion", ex);
      }
    }
    this.signer = s;
    this.verifier = v;
    log.info("SessionTokenService initialized: enabled={}", enabled);
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** Emite un session token para el usuario. Lanza IllegalStateException si
   * el servicio está disabled — el caller debe chequear isEnabled() antes. */
  public String issue(Long userId) {
    if (!enabled) {
      throw new IllegalStateException("session token service disabled");
    }
    Instant now = Instant.now();
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(String.valueOf(userId))
        .issuer(ISSUER)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plus(SESSION_TTL)))
        .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException ex) {
      throw new IllegalStateException("no se pudo firmar el session token", ex);
    }
    return jwt.serialize();
  }

  /** Valida firma + expiración y devuelve el userId. Empty si el token es
   * inválido, expirado, manipulado, o el servicio está disabled. */
  public Optional<Long> validate(String sessionToken) {
    if (!enabled || sessionToken == null || sessionToken.isBlank()) {
      return Optional.empty();
    }
    try {
      SignedJWT jwt = SignedJWT.parse(sessionToken);
      if (!jwt.verify(verifier)) {
        return Optional.empty();
      }
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      Date exp = claims.getExpirationTime();
      if (exp == null || exp.before(new Date())) {
        return Optional.empty();
      }
      String subject = claims.getSubject();
      if (subject == null) {
        return Optional.empty();
      }
      return Optional.of(Long.valueOf(subject));
    } catch (ParseException | JOSEException | NumberFormatException ex) {
      log.warn("session token validation failed: {}", ex.getMessage());
      return Optional.empty();
    }
  }
}
