package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifica firma RS256 contra JWKS mockeado (clave RSA propia, sin pegarle
 * a Google), audience, issuer y expiración. Usa el constructor de test
 * package-private de GoogleIdTokenVerifierService que acepta un JWKSource
 * a medida.
 */
class GoogleIdTokenVerifierServiceTest {

  private static final String CLIENT_ID = "test-client-id.apps.googleusercontent.com";
  private static final String KEY_ID = "test-key-1";

  private RSAKey rsaKey;
  private RSAKey otherRsaKey;
  private GoogleIdTokenVerifierService verifier;

  @BeforeEach
  void setUp() throws Exception {
    rsaKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
    otherRsaKey = new RSAKeyGenerator(2048).keyID("other-key").generate();

    ImmutableJWKSet<SecurityContext> jwkSource =
        new ImmutableJWKSet<>(new JWKSet(rsaKey.toPublicJWK()));
    verifier = newVerifier(CLIENT_ID, jwkSource);
  }

  private static GoogleIdTokenVerifierService newVerifier(String clientId, ImmutableJWKSet<SecurityContext> src)
      throws Exception {
    Constructor<GoogleIdTokenVerifierService> ctor = GoogleIdTokenVerifierService.class
        .getDeclaredConstructor(String.class, com.nimbusds.jose.jwk.source.JWKSource.class);
    ctor.setAccessible(true);
    return ctor.newInstance(clientId, src);
  }

  private String signedToken(RSAKey signingKey, String issuer, String audience, Instant exp, String sub)
      throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(sub)
        .issuer(issuer)
        .audience(audience)
        .claim("email", "cliente@example.com")
        .claim("name", "Sofia Cliente")
        .claim("picture", "https://example.com/pic.jpg")
        .expirationTime(Date.from(exp))
        .issueTime(Date.from(Instant.now()))
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
        claims);
    jwt.sign(new RSASSASigner(signingKey));
    return jwt.serialize();
  }

  @Test
  void validTokenUpserts() throws Exception {
    String token = signedToken(rsaKey, "accounts.google.com", CLIENT_ID, Instant.now().plusSeconds(3600), "google-sub-123");

    Optional<GoogleIdTokenVerifierService.GoogleIdentity> result = verifier.verify(token);

    assertThat(result).isPresent();
    assertThat(result.get().sub()).isEqualTo("google-sub-123");
    assertThat(result.get().email()).isEqualTo("cliente@example.com");
  }

  @Test
  void httpsIssuerAlsoAccepted() throws Exception {
    String token = signedToken(rsaKey, "https://accounts.google.com", CLIENT_ID, Instant.now().plusSeconds(3600), "google-sub-456");

    Optional<GoogleIdTokenVerifierService.GoogleIdentity> result = verifier.verify(token);

    assertThat(result).isPresent();
  }

  @Test
  void invalidSignatureRejected() throws Exception {
    // Firmado con una clave que NO está en el JWKSource del verifier.
    String token = signedToken(otherRsaKey, "accounts.google.com", CLIENT_ID, Instant.now().plusSeconds(3600), "google-sub-789");

    Optional<GoogleIdTokenVerifierService.GoogleIdentity> result = verifier.verify(token);

    assertThat(result).isEmpty();
  }

  @Test
  void wrongAudienceRejected() throws Exception {
    String token = signedToken(rsaKey, "accounts.google.com", "otro-client-id.apps.googleusercontent.com",
        Instant.now().plusSeconds(3600), "google-sub-999");

    Optional<GoogleIdTokenVerifierService.GoogleIdentity> result = verifier.verify(token);

    assertThat(result).isEmpty();
  }

  @Test
  void expiredTokenRejected() throws Exception {
    String token = signedToken(rsaKey, "accounts.google.com", CLIENT_ID, Instant.now().minusSeconds(60), "google-sub-000");

    Optional<GoogleIdTokenVerifierService.GoogleIdentity> result = verifier.verify(token);

    assertThat(result).isEmpty();
  }

  @Test
  void disabledWithoutClientId() throws Exception {
    ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey.toPublicJWK()));
    GoogleIdTokenVerifierService disabled = newVerifier("", jwkSource);

    assertThat(disabled.isEnabled()).isFalse();
    assertThat(disabled.verify("cualquier-token")).isEmpty();
  }
}
