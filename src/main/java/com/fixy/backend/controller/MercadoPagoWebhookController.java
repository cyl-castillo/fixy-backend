package com.fixy.backend.controller;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.MercadoPagoService;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook de notificaciones de pago de Mercado Pago (P0-1 / H1.3).
 *
 * Seguridad: NUNCA confiamos en el contenido del payload/query params de la
 * notificación (Mercado Pago solo indica "hay novedades sobre este pago,
 * andá a buscarlo"). Tomamos el payment id y re-consultamos
 * GET /v1/payments/{id} directamente a la API de MP con nuestro access
 * token antes de tocar cualquier estado.
 *
 * Idempotencia: si el LeadPayment ya está PAID, un webhook repetido sobre
 * el mismo pago responde 200 sin efectos (no reemite el evento
 * COMMISSION_PAID ni pisa paidAt).
 *
 * Mercado Pago puede notificar con distintos formatos de query params según
 * el tipo de integración (IPN clásico: topic/id; webhooks nuevos: type/data.id).
 * Aceptamos ambos.
 */
@RestController
@RequestMapping("/api/webhooks/mercadopago")
public class MercadoPagoWebhookController {

  private static final Logger log = LoggerFactory.getLogger(MercadoPagoWebhookController.class);

  private final MercadoPagoService mercadoPagoService;
  private final LeadPaymentRepository leadPaymentRepository;
  private final LeadRepository leadRepository;
  private final LeadTimelineService timelineService;

  public MercadoPagoWebhookController(
      MercadoPagoService mercadoPagoService,
      LeadPaymentRepository leadPaymentRepository,
      LeadRepository leadRepository,
      LeadTimelineService timelineService
  ) {
    this.mercadoPagoService = mercadoPagoService;
    this.leadPaymentRepository = leadPaymentRepository;
    this.leadRepository = leadRepository;
    this.timelineService = timelineService;
  }

  @PostMapping
  public ResponseEntity<String> receive(
      @RequestParam(value = "type", required = false) String type,
      @RequestParam(value = "topic", required = false) String topic,
      @RequestParam(value = "data.id", required = false) String dataId,
      @RequestParam(value = "id", required = false) String legacyId
  ) {
    // Devolvemos 200 siempre que el procesamiento no reviente: MP reintenta
    // agresivamente notificaciones no confirmadas con 2xx.
    String kind = type != null ? type : topic;
    String paymentId = dataId != null ? dataId : legacyId;

    if (!"payment".equals(kind) || paymentId == null || paymentId.isBlank()) {
      log.info("mercadopago webhook ignorado: type/topic={} id={}", kind, paymentId);
      return ResponseEntity.ok("ignored");
    }

    try {
      processPaymentNotification(paymentId);
    } catch (Exception ex) {
      log.warn("mercadopago webhook processing failed for paymentId={}: {}", paymentId, ex.getMessage());
    }
    return ResponseEntity.ok("OK");
  }

  private void processPaymentNotification(String paymentId) {
    Optional<MercadoPagoService.PaymentStatusResult> result = mercadoPagoService.fetchPayment(paymentId);
    if (result.isEmpty()) {
      log.warn("mercadopago webhook: no pude re-consultar paymentId={}", paymentId);
      return;
    }

    MercadoPagoService.PaymentStatusResult payment = result.get();
    if (payment.externalReference() == null || payment.externalReference().isBlank()) {
      log.warn("mercadopago webhook: paymentId={} sin external_reference", paymentId);
      return;
    }

    Long leadPaymentId;
    try {
      leadPaymentId = Long.valueOf(payment.externalReference());
    } catch (NumberFormatException ex) {
      log.warn("mercadopago webhook: external_reference invalido '{}' para paymentId={}",
          payment.externalReference(), paymentId);
      return;
    }

    Optional<LeadPayment> leadPaymentOpt = leadPaymentRepository.findById(leadPaymentId);
    if (leadPaymentOpt.isEmpty()) {
      log.warn("mercadopago webhook: LeadPayment {} no encontrado (paymentId={})", leadPaymentId, paymentId);
      return;
    }

    LeadPayment leadPayment = leadPaymentOpt.get();

    if (!"approved".equals(payment.status())) {
      log.info("mercadopago webhook: paymentId={} status={} (no approved todavia) para LeadPayment {}",
          paymentId, payment.status(), leadPaymentId);
      return;
    }

    // Transición atómica en la base: MP puede mandar la misma notificación
    // dos veces casi simultáneas y un check-then-act en memoria deja pasar a
    // ambas (evento COMMISSION_PAID duplicado — visto en sandbox). Solo la
    // invocación que efectivamente transiciona la fila (1 fila afectada)
    // emite el evento.
    int transitioned = leadPaymentRepository.markPaidIfNotAlready(
        leadPaymentId, paymentId, OffsetDateTime.now(), CommissionStatus.PAID);
    if (transitioned == 0) {
      log.info("mercadopago webhook: LeadPayment {} ya estaba PAID, notificación repetida ignorada", leadPaymentId);
      return;
    }

    Lead lead = leadRepository.findById(leadPayment.getLeadId()).orElse(null);
    if (lead != null) {
      timelineService.appendEvent(lead, "COMMISSION_PAID", "system",
          "Comisión %s %s pagada (MP payment %s)".formatted(
              leadPayment.getCurrency(), leadPayment.getCommissionAmount(), paymentId));
    } else {
      log.warn("mercadopago webhook: lead {} no encontrado para LeadPayment {} (evento no emitido)",
          leadPayment.getLeadId(), leadPaymentId);
    }

    log.info("mercadopago webhook: LeadPayment {} marcado PAID (paymentId={})", leadPaymentId, paymentId);
  }
}
