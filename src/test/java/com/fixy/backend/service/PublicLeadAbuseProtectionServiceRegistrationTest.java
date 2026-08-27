package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ventanas propias de rate limit del autoregistro público (Fase 1+2 "puerta
 * única de registro"): proveedor (agregado por primera vez a
 * {@code ProviderRegistrationService}, no tenía freno) y comercio (endpoint
 * nuevo). Unit test directo (sin Spring) con límites chicos a propósito,
 * mismo motivo que {@link PublicLeadAbuseProtectionServiceMerchantPanelTest}:
 * la instancia real del bean (vía contexto de test) comparte estado con toda
 * la suite que pega a {@code /api/public/providers/register} y
 * {@code /api/public/businesses/register} desde la misma IP mockeada.
 */
class PublicLeadAbuseProtectionServiceRegistrationTest {

  private PublicLeadAbuseProtectionService service(int providerRegistrationMax, int businessRegistrationMax) {
    return new PublicLeadAbuseProtectionService(
        200, 600, 200, 600, 200, 600, 200, 600, 200, 60, 200, 600, 200, 60,
        providerRegistrationMax, 600, businessRegistrationMax, 600);
  }

  @Test
  void providerRegistration_permiteHastaElMaximoConfiguradoYLuegoRechaza429() {
    PublicLeadAbuseProtectionService service = service(2, 200);
    service.validateProviderRegistration("10.2.0.1");
    service.validateProviderRegistration("10.2.0.1");

    assertThatThrownBy(() -> service.validateProviderRegistration("10.2.0.1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void businessRegistration_permiteHastaElMaximoConfiguradoYLuegoRechaza429() {
    PublicLeadAbuseProtectionService service = service(200, 2);
    service.validateBusinessRegistration("10.2.0.2");
    service.validateBusinessRegistration("10.2.0.2");

    assertThatThrownBy(() -> service.validateBusinessRegistration("10.2.0.2"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void lasDosVentanasSonIndependientesEntreSiYDeIpsDistintas() {
    PublicLeadAbuseProtectionService service = service(1, 1);
    service.validateProviderRegistration("10.2.0.3");
    // Cupo de proveedor agotado para esta IP, pero comercio sigue intacto.
    service.validateBusinessRegistration("10.2.0.3");

    // Otra IP no se ve afectada por el cupo agotado de la primera.
    service.validateProviderRegistration("10.2.0.4");
    service.validateBusinessRegistration("10.2.0.4");
  }
}
