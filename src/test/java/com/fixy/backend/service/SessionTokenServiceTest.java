package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionTokenServiceTest {

  private static final String SECRET = "test-session-secret-at-least-32-bytes-long-for-hmac";

  @Test
  void validTokenResolvesUserId() {
    SessionTokenService service = new SessionTokenService(SECRET);

    String token = service.issue(42L);
    Optional<Long> userId = service.validate(token);

    assertThat(userId).contains(42L);
  }

  @Test
  void tamperedTokenRejected() {
    SessionTokenService service = new SessionTokenService(SECRET);
    String token = service.issue(42L);

    // Manipulamos el payload (segunda sección del JWT) sin resignar.
    String[] parts = token.split("\\.");
    String tamperedPayload = parts[1].replace('A', 'B');
    String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

    assertThat(service.validate(tampered)).isEmpty();
  }

  @Test
  void differentSecretRejected() {
    SessionTokenService issuer = new SessionTokenService(SECRET);
    SessionTokenService otherSecretVerifier = new SessionTokenService("other-secret-also-at-least-32-bytes-long");

    String token = issuer.issue(7L);

    assertThat(otherSecretVerifier.validate(token)).isEmpty();
  }

  @Test
  void disabledWithoutSecret() {
    SessionTokenService service = new SessionTokenService("");

    assertThat(service.isEnabled()).isFalse();
    assertThat(service.validate("cualquier-token")).isEmpty();
  }

  @Test
  void blankTokenRejected() {
    SessionTokenService service = new SessionTokenService(SECRET);

    assertThat(service.validate(null)).isEmpty();
    assertThat(service.validate("")).isEmpty();
  }
}
