package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Integración con Mercado Pago Checkout Pro vía REST puro (sin SDK), mismo
 * patrón que {@link WhatsAppService}: si no hay access token configurado, el
 * servicio queda "disabled" y los métodos devuelven Optional.empty()/false
 * en vez de fallar, para no romper el flujo de completar un lead antes de
 * que Carlos cargue las credenciales de Mercado Pago.
 *
 * Config requerida:
 *   fixy.payments.mp-access-token   (access token de MP, sandbox o prod)
 *
 * Doc de referencia: https://www.mercadopago.com.uy/developers/es/reference/preferences/_checkout_preferences/post
 */
@Service
public class MercadoPagoService {

  private static final Logger log = LoggerFactory.getLogger(MercadoPagoService.class);

  private final WebClient client;
  private final ObjectMapper objectMapper;
  private final String accessToken;
  private final boolean enabled;

  public MercadoPagoService(
      ObjectMapper objectMapper,
      @Value("${fixy.payments.mp-access-token:}") String accessToken
  ) {
    this.objectMapper = objectMapper;
    this.accessToken = accessToken;
    this.enabled = accessToken != null && !accessToken.isBlank();
    this.client = WebClient.builder()
        .baseUrl("https://api.mercadopago.com")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    log.info("MercadoPagoService initialized: enabled={}", enabled);
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Crea una preference de Checkout Pro para cobrar la comisión de Fixy al
   * proveedor. {@code externalReference} debe ser el id del LeadPayment (se
   * usa para reconciliar en el webhook sin confiar en el payload).
   *
   * @return preferenceId + init_point (link de pago), o Optional.empty() si
   *         MP no está configurado o la llamada falla.
   */
  public java.util.Optional<PreferenceResult> createCommissionPreference(
      Long leadPaymentId,
      String externalReference,
      BigDecimal commissionAmount,
      String currency,
      String description
  ) {
    if (!enabled) {
      log.warn("mercadopago disabled, skip createCommissionPreference for leadPayment={}", leadPaymentId);
      return java.util.Optional.empty();
    }
    Map<String, Object> item = Map.of(
        "title", description,
        "quantity", 1,
        "currency_id", currency,
        "unit_price", commissionAmount.doubleValue()
    );
    Map<String, Object> body = Map.of(
        "items", List.of(item),
        "external_reference", externalReference,
        "notification_url", "" // Carlos completa la URL pública real al configurar el webhook en prod.
    );
    try {
      String response = client.post()
          .uri("/checkout/preferences")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .bodyValue(body)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(15))
          .block();
      if (response == null) {
        log.warn("mercadopago createCommissionPreference leadPayment={}: empty response", leadPaymentId);
        return java.util.Optional.empty();
      }
      JsonNode root = objectMapper.readTree(response);
      String preferenceId = root.path("id").asText(null);
      String initPoint = root.path("init_point").asText(null);
      if (preferenceId == null || initPoint == null) {
        log.warn("mercadopago createCommissionPreference leadPayment={}: unexpected response {}",
            leadPaymentId, response.length() > 300 ? response.substring(0, 300) : response);
        return java.util.Optional.empty();
      }
      log.info("mercadopago preference created leadPayment={} preferenceId={}", leadPaymentId, preferenceId);
      return java.util.Optional.of(new PreferenceResult(preferenceId, initPoint));
    } catch (Exception ex) {
      log.warn("mercadopago createCommissionPreference leadPayment={} failed: {}", leadPaymentId, ex.getMessage());
      return java.util.Optional.empty();
    }
  }

  /**
   * Re-consulta un pago por id directamente a MP (nunca confiar en el
   * payload del webhook). Devuelve empty si MP no está configurado, la
   * llamada falla, o el pago no existe.
   */
  public java.util.Optional<PaymentStatusResult> fetchPayment(String paymentId) {
    if (!enabled) {
      log.warn("mercadopago disabled, skip fetchPayment {}", paymentId);
      return java.util.Optional.empty();
    }
    try {
      String response = client.get()
          .uri("/v1/payments/{id}", paymentId)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(15))
          .block();
      if (response == null) {
        return java.util.Optional.empty();
      }
      JsonNode root = objectMapper.readTree(response);
      String status = root.path("status").asText(null);
      String externalReference = root.path("external_reference").asText(null);
      if (status == null) {
        return java.util.Optional.empty();
      }
      return java.util.Optional.of(new PaymentStatusResult(paymentId, status, externalReference));
    } catch (Exception ex) {
      log.warn("mercadopago fetchPayment {} failed: {}", paymentId, ex.getMessage());
      return java.util.Optional.empty();
    }
  }

  public record PreferenceResult(String preferenceId, String initPoint) {
  }

  public record PaymentStatusResult(String paymentId, String status, String externalReference) {
  }
}
