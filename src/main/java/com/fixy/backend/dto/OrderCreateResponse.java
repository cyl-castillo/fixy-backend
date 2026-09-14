package com.fixy.backend.dto;

/** Respuesta 201 del pedido estructurado (contrato §3). */
public record OrderCreateResponse(
    Long leadId,
    String accessToken,
    String serviceCode,
    String serviceName,
    Integer priceFrom,
    Integer priceTo,
    /** CONTACTING | NO_PROVIDERS — resultado del matching disparado en el acto. */
    String matchStatus
) {
}
