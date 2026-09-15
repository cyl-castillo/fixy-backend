package com.fixy.backend.dto;

/**
 * {@code POST /api/public/remote-care/requests} (contrato §B.3). {@code
 * website} es el honeypot — mismo contrato que {@code
 * PublicOfferSubmissionRequest}: un bot que lo completa recibe 201 igual
 * (nunca delatarlo) pero no persiste nada (ver {@code
 * RemoteCareRequestService.create}).
 */
public record RemoteCarePlanRequest(
    String ownerName,
    String phone,
    String email,
    String zone,
    String address,
    String onSiteName,
    String onSitePhone,
    String notes,
    String website
) {
}
