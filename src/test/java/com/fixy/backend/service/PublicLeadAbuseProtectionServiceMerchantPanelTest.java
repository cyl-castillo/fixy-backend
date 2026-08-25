package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ventana propia de rate limit del panel self-service del comercio (Fase 5)
 * — unit test directo (sin Spring) con límites chicos a propósito, mismo
 * motivo que {@link PublicLeadAbuseProtectionServiceOfferInquiryTest}: la
 * instancia real del bean (vía contexto de test) comparte estado con TODO el
 * resto de la suite que pega a {@code /api/public/merchant/**} desde la
 * misma IP mockeada.
 */
class PublicLeadAbuseProtectionServiceMerchantPanelTest {

  private PublicLeadAbuseProtectionService service(int merchantPanelMax) {
    return new PublicLeadAbuseProtectionService(200, 600, 200, 600, 200, 600, 200, 600, merchantPanelMax, 600);
  }

  @Test
  void permiteHastaElMaximoConfiguradoYLuegoRechaza429() {
    PublicLeadAbuseProtectionService service = service(2);
    service.validateMerchantPanel("10.0.0.2");
    service.validateMerchantPanel("10.0.0.2");

    assertThatThrownBy(() -> service.validateMerchantPanel("10.0.0.2"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void ipsDistintasTienenCupoIndependiente() {
    PublicLeadAbuseProtectionService service = service(1);
    service.validateMerchantPanel("10.0.0.3");

    // otra IP no se ve afectada por el cupo agotado de la primera.
    service.validateMerchantPanel("10.0.0.4");
  }
}
