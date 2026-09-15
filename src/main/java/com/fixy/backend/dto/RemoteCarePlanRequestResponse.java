package com.fixy.backend.dto;

/** Respuesta 201 de {@code POST /api/public/remote-care/requests} (contrato §B.3). */
public record RemoteCarePlanRequestResponse(Long planId, String accessToken) {
}
