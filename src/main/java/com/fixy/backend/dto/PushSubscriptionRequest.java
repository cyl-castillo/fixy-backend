package com.fixy.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Forma tal cual la serializa {@code PushSubscription.toJSON()} del
 * navegador: {@code {endpoint, keys: {p256dh, auth}}}.
 */
public record PushSubscriptionRequest(
    @NotBlank String endpoint,
    @Valid Keys keys
) {
  public record Keys(
      @NotBlank String p256dh,
      @NotBlank String auth
  ) {
  }
}
