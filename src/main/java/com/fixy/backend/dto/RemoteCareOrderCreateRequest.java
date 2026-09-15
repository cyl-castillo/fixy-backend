package com.fixy.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/public/remote-care/plans/{id}/orders?token=} (contrato
 * §B.3): mismo body que {@code POST /api/public/orders} menos {@code
 * name/phone/remote/onSiteContact} — se toman del plan.
 */
public record RemoteCareOrderCreateRequest(
    @NotBlank(message = "serviceCode is required") String serviceCode,
    @NotBlank(message = "zone is required") String zone,
    @NotBlank(message = "timeWindow is required") String timeWindow,
    String notes,
    String channel
) {
}
