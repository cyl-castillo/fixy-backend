package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Pedido estructurado con precio cerrado (contrato §3):
 * {@code POST /api/public/orders}.
 */
public record OrderCreateRequest(
    @NotBlank(message = "serviceCode is required") String serviceCode,
    @NotBlank(message = "zone is required") String zone,
    @NotBlank(message = "timeWindow is required") String timeWindow,
    @NotBlank(message = "name is required") String name,
    @NotBlank(message = "phone is required") String phone,
    String notes,
    Boolean remote,
    OnSiteContact onSiteContact,
    String channel,
    /** Tier 1 (contrato §C.1/§C.2): "solo el técnico, sin garantía" — el
     * cliente eligió no pagar el cargo de servicio Fixy al reservar. Default
     * false (con garantía) cuando no viene en el body. */
    Boolean serviceFeeOptOut
) {
  /** Quién abre la puerta cuando {@code remote=true}. */
  public record OnSiteContact(String name, String phone) {
  }
}
