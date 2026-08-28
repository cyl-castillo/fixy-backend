package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ventana propia de rate limit + validación de longitud del alta pública de
 * ofertas (fase 2 "ofertas protagonistas") — unit test directo (sin Spring),
 * mismo criterio que {@link PublicLeadAbuseProtectionServiceOfferInquiryTest}:
 * la instancia real (vía contexto de test) comparte estado con toda la suite
 * que pega a {@code /api/public/offer-submissions} desde la misma IP
 * mockeada, así que el 429 se prueba acá con límites chicos a propósito.
 */
class PublicLeadAbuseProtectionServiceOfferSubmissionTest {

  private PublicLeadAbuseProtectionService service(int offerSubmissionMax) {
    return new PublicLeadAbuseProtectionService(200, 600, 200, 600, offerSubmissionMax, 600, 200, 600, 200, 60, 200, 600, 200, 60, 200, 600, 200, 600);
  }

  private void validOk(PublicLeadAbuseProtectionService service, String ip) {
    service.validateOfferSubmission(ip, "Comercio Test", "099111222", "otro", "Solymar",
        "20% off en todo", null, null, null);
  }

  @Test
  void permiteHastaElMaximoConfiguradoYLuegoRechaza429() {
    PublicLeadAbuseProtectionService service = service(2);
    validOk(service, "10.1.0.1");
    validOk(service, "10.1.0.1");

    assertThatThrownBy(() -> validOk(service, "10.1.0.1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void laVentanaDeSubmissionsNoComparteCupoConLaDeInquiriesNiLeads() {
    PublicLeadAbuseProtectionService service = service(1);
    validOk(service, "10.1.0.2");
    // Cupo de submissions agotado para esta IP, pero los otros dos cupos
    // (maxRequestsPerWindow=200, offerInquiryMax=200 en este test) siguen
    // intactos — ventanas separadas.
    service.validate("10.1.0.2", "Un problema real de al menos doce caracteres");
    service.validateOfferInquiry("10.1.0.2", "Vecina", "099111222", "Consulta válida de largo suficiente");
  }

  @Test
  void ipsDistintasNoComparteCupoDeSubmissions() {
    PublicLeadAbuseProtectionService service = service(1);
    validOk(service, "10.1.0.3");
    validOk(service, "10.1.0.4");
  }

  @Test
  void businessNameVacioOMayorAlMaximoEsRechazado() {
    PublicLeadAbuseProtectionService service = service(200);
    assertThatThrownBy(() -> service.validateOfferSubmission("10.1.1.1", "", "099111222", "otro", "Solymar",
        "Titulo", null, null, null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));

    String tooLongName = "a".repeat(151);
    assertThatThrownBy(() -> service.validateOfferSubmission("10.1.1.2", tooLongName, "099111222", "otro",
        "Solymar", "Titulo", null, null, null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void whatsappVacioOMayorAlMaximoEsRechazado() {
    PublicLeadAbuseProtectionService service = service(200);
    assertThatThrownBy(() -> service.validateOfferSubmission("10.1.1.3", "Comercio", "", "otro", "Solymar",
        "Titulo", null, null, null))
        .isInstanceOf(ResponseStatusException.class);

    String tooLongWhatsapp = "9".repeat(31);
    assertThatThrownBy(() -> service.validateOfferSubmission("10.1.1.4", "Comercio", tooLongWhatsapp, "otro",
        "Solymar", "Titulo", null, null, null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void categoryVaciaEsRechazada() {
    PublicLeadAbuseProtectionService service = service(200);
    assertThatThrownBy(() -> service.validateOfferSubmission("10.1.1.5", "Comercio", "099111222", "", "Solymar",
        "Titulo", null, null, null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void zoneVaciaEsRechazada() {
    PublicLeadAbuseProtectionService service = service(200);
    assertThatThrownBy(() -> service.validateOfferSubmission("10.1.1.6", "Comercio", "099111222", "otro", "",
        "Titulo", null, null, null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void titleVacioOMayorAlMaximoEsRechazado() {
    PublicLeadAbuseProtectionService service = service(200);
    assertThatThrownBy(() -> service.validateOfferSubmission("10.1.1.7", "Comercio", "099111222", "otro",
        "Solymar", "", null, null, null))
        .isInstanceOf(ResponseStatusException.class);

    String tooLongTitle = "a".repeat(151);
    assertThatThrownBy(() -> service.validateOfferSubmission("10.1.1.8", "Comercio", "099111222", "otro",
        "Solymar", tooLongTitle, null, null, null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void descripcionMuyLargaEsRechazada() {
    PublicLeadAbuseProtectionService service = service(200);
    String tooLong = "a".repeat(1001);
    assertThatThrownBy(() -> service.validateOfferSubmission("10.1.1.9", "Comercio", "099111222", "otro",
        "Solymar", "Titulo", null, null, tooLong))
        .isInstanceOf(ResponseStatusException.class);
  }
}
