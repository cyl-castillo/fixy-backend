package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ventana propia de rate limit + validación de longitud del mini-form de
 * consulta de oferta (FIXY_OFERTAS_CTA_DESIGN.md §6) — unit test directo
 * (sin Spring) con límites chicos a propósito: la instancia del bean real
 * (vía contexto de test) comparte estado con TODO el resto de la suite que
 * pega a {@code /api/public/offers/{id}/inquiries} desde la misma IP
 * mockeada, así que probar el 429 ahí adentro rompería esos otros tests.
 */
class PublicLeadAbuseProtectionServiceOfferInquiryTest {

  private PublicLeadAbuseProtectionService service(int offerInquiryMax) {
    return new PublicLeadAbuseProtectionService(200, 600, offerInquiryMax, 600, 200, 600);
  }

  @Test
  void permiteHastaElMaximoConfiguradoYLuegoRechaza429() {
    PublicLeadAbuseProtectionService service = service(2);
    service.validateOfferInquiry("10.0.0.1", "Vecina", "099111222", "Consulta válida de largo suficiente");
    service.validateOfferInquiry("10.0.0.1", "Vecina", "099111222", "Consulta válida de largo suficiente");

    assertThatThrownBy(() ->
        service.validateOfferInquiry("10.0.0.1", "Vecina", "099111222", "Consulta válida de largo suficiente"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void laVentanaDeInquiriesNoComparteCupoConLaDeLeads() {
    PublicLeadAbuseProtectionService service = service(1);
    service.validateOfferInquiry("10.0.0.2", "Vecina", "099111222", "Consulta válida de largo suficiente");
    // Cupo de inquiries agotado para esta IP, pero el cupo general de leads
    // (maxRequestsPerWindow=200 en este test) sigue intacto — ventanas
    // separadas, un vecino navegando ofertas no gasta su cupo de pedir un
    // servicio por tocar "Consultar".
    service.validate("10.0.0.2", "Un problema real de al menos doce caracteres");
  }

  @Test
  void ipsDistintasNoComparteCupoDeInquiries() {
    PublicLeadAbuseProtectionService service = service(1);
    service.validateOfferInquiry("10.0.0.3", "Vecina", "099111222", "Consulta válida de largo suficiente");
    service.validateOfferInquiry("10.0.0.4", "Vecina", "099111222", "Consulta válida de largo suficiente");
  }

  @Test
  void mensajeMenorAlMinimoEsRechazado() {
    PublicLeadAbuseProtectionService service = service(200);
    assertThatThrownBy(() -> service.validateOfferInquiry("10.0.1.1", "Vecina", "099111222", "hi"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void mensajeMayorAlMaximoEsRechazado() {
    PublicLeadAbuseProtectionService service = service(200);
    String tooLong = "a".repeat(501);
    assertThatThrownBy(() -> service.validateOfferInquiry("10.0.1.2", "Vecina", "099111222", tooLong))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void nombreVacioOMayorAlMaximoEsRechazado() {
    PublicLeadAbuseProtectionService service = service(200);
    assertThatThrownBy(() -> service.validateOfferInquiry("10.0.1.3", "", "099111222", "Consulta válida de largo suficiente"))
        .isInstanceOf(ResponseStatusException.class);
    String tooLongName = "a".repeat(101);
    assertThatThrownBy(() -> service.validateOfferInquiry("10.0.1.4", tooLongName, "099111222", "Consulta válida de largo suficiente"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void whatsappVacioOMayorAlMaximoEsRechazado() {
    PublicLeadAbuseProtectionService service = service(200);
    assertThatThrownBy(() -> service.validateOfferInquiry("10.0.1.5", "Vecina", "", "Consulta válida de largo suficiente"))
        .isInstanceOf(ResponseStatusException.class);
    String tooLongWhatsapp = "9".repeat(101);
    assertThatThrownBy(() -> service.validateOfferInquiry("10.0.1.6", "Vecina", tooLongWhatsapp, "Consulta válida de largo suficiente"))
        .isInstanceOf(ResponseStatusException.class);
  }
}
