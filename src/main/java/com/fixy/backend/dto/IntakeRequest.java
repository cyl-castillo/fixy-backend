package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record IntakeRequest(
    @NotBlank(message = "message is required") String message,
    String contactName,
    String phone,
    String channel,
    String serviceCategory,
    String zone,
    String urgency,
    String address,
    String details
) {
  public IntakeRequest(String message, String contactName, String phone, String channel) {
    this(message, contactName, phone, channel, null, null, null, null, null);
  }
}
