package com.fixy.backend.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.ResourceRetriever;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Verifica ID tokens (JWT RS256) emitidos por Google Identity Services:
 * firma contra las claves públicas JWKS de Google, audience == nuestro
 * client id, issuer accounts.google.com/https://accounts.google.com, y
 * expiración. Mismo patrón "disabled si falta config" que
 * {@link MercadoPagoService}: sin GOOGLE_CLIENT_ID, isEnabled() es false y
 * el caller (AuthService) debe devolver 503 en vez de invocar verify().
 *
 * Las claves JWKS se cachean en memoria (nimbus RemoteJWKSet, TTL ~6h) para
 * no pegarle a Google en cada login.
 */
@Service
public class GoogleIdTokenVerifierService {

  private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifierService.class);

  static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
  private static final Set<String> ALLOWED_ISSUERS = Set.of(
      "accounts.google.com", "https://accounts.google.com");
  private static final Duration JWKS_CACHE_TTL = Duration.ofHours(6);

  private final String clientId;
  private final boolean enabled;
  private final DefaultJWTProcessor<SecurityContext> jwtProcessor;

  @Autowired
  public GoogleIdTokenVerifierService(@Value("${fixy.auth.google-client-id:}") String clientId) {
    this(clientId, defaultJwkSource());
  }

  /** Constructor de test: permite inyectar un JWKSource propio (claves de
   * prueba) en vez de pegarle a la URL real de Google. */
  GoogleIdTokenVerifierService(String clientId, JWKSource<SecurityContext> jwkSource) {
    this.clientId = clientId;
    this.enabled = clientId != null && !clientId.isBlank();
    this.jwtProcessor = new DefaultJWTProcessor<>();
    if (jwkSource != null) {
      this.jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(
          com.nimbusds.jose.JWSAlgorithm.RS256, jwkSource));
    }
    this.jwtProcessor.setJWTClaimsSetVerifier(buildClaimsVerifier());
    log.info("GoogleIdTokenVerifierService initialized: enabled={}", enabled);
  }

  private static JWKSource<SecurityContext> defaultJwkSource() {
    try {
      ResourceRetriever retriever = new DefaultResourceRetriever(5000, 5000);
      return JWKSourceBuilder
          .create(new URL(JWKS_URL), retriever)
          .cache(JWKS_CACHE_TTL.toMillis(), JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT)
          .build();
    } catch (MalformedURLException ex) {
      throw new IllegalStateException("URL de JWKS de Google inválida", ex);
    }
  }

  private JWTClaimsSetVerifier<SecurityContext> buildClaimsVerifier() {
    // exp/nbf los valida nimbus internamente en DefaultJWTClaimsVerifier;
    // acá encima forzamos issuer permitido y audience == nuestro client id.
    JWTClaimsSet.Builder exactMatch = new JWTClaimsSet.Builder();
    Set<String> requiredClaims = Set.of("sub", "email", "exp", "iss", "aud");
    return new DefaultJWTClaimsVerifier<>(exactMatch.build(), requiredClaims) {
      @Override
      public void verify(JWTClaimsSet claimsSet, SecurityContext context) throws BadJWTException {
        super.verify(claimsSet, context);
        String issuer = claimsSet.getIssuer();
        if (issuer == null || !ALLOWED_ISSUERS.contains(issuer)) {
          throw new BadJWTException("issuer inválido: " + issuer);
        }
        java.util.List<String> audience = claimsSet.getAudience();
        if (audience == null || !audience.contains(clientId)) {
          throw new BadJWTException("audience inválida");
        }
      }
    };
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** Verifica firma, issuer, audience y expiración. Empty si el token es
   * inválido/expirado/mal formado por cualquier motivo (nunca lanza). */
  public Optional<GoogleIdentity> verify(String idToken) {
    if (!enabled) {
      log.warn("google id token verification disabled (sin GOOGLE_CLIENT_ID)");
      return Optional.empty();
    }
    if (idToken == null || idToken.isBlank()) {
      return Optional.empty();
    }
    try {
      SignedJWT signedJWT = SignedJWT.parse(idToken);
      JWTClaimsSet claims = jwtProcessor.process(signedJWT, null);
      String sub = claims.getSubject();
      String email = claims.getStringClaim("email");
      String name = claims.getStringClaim("name");
      String picture = claims.getStringClaim("picture");
      if (sub == null || sub.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new GoogleIdentity(sub, email, name, picture));
    } catch (ParseException | BadJOSEException | JOSEException ex) {
      log.warn("google id token verification failed: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  /** exp claim helper usado solo para logging/debug si hiciera falta. */
  static boolean isExpired(Date exp) {
    return exp != null && exp.before(new Date());
  }

  public record GoogleIdentity(String sub, String email, String name, String pictureUrl) {
  }
}
