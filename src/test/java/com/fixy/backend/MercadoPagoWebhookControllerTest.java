package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.MercadoPagoService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * H1.3: el webhook de Mercado Pago nunca confía en el payload de la
 * notificación — re-consulta el pago vía MercadoPagoService (mockeado acá)
 * y solo entonces actualiza el LeadPayment. Verifica idempotencia: dos
 * notificaciones del mismo pago aprobado producen un único PAID.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MercadoPagoWebhookControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private LeadRepository leadRepository;

  @Autowired
  private ProviderRepository providerRepository;

  @Autowired
  private LeadPaymentRepository leadPaymentRepository;

  @Autowired
  private LeadEventRepository leadEventRepository;

  @MockitoBean
  private MercadoPagoService mercadoPagoService;

  private LeadPayment createPendingLeadPayment() {
    return createLeadPayment(CommissionStatus.PENDING);
  }

  private LeadPayment createLeadPayment(CommissionStatus initialStatus) {
    Lead lead = new Lead();
    lead.setProblem("Problema de prueba webhook MP");
    lead.setPhone("099800000");
    lead.setChannel("whatsapp");
    lead.setStatus(LeadStatus.COMPLETED);
    lead = leadRepository.save(lead);

    Provider provider = new Provider();
    provider.setName("Proveedor Webhook MP Test");
    provider.setPhone("099800111");
    provider.setCategories("plomeria");
    provider = providerRepository.save(provider);

    LeadPayment payment = new LeadPayment();
    payment.setLeadId(lead.getId());
    payment.setProviderId(provider.getId());
    payment.setAmountCharged(new BigDecimal("2500.00"));
    payment.setCommissionRate(new BigDecimal("0.1000"));
    payment.setCommissionAmount(new BigDecimal("250.00"));
    payment.setCommissionStatus(initialStatus);
    return leadPaymentRepository.save(payment);
  }

  @Test
  void shouldMarkPaidOnApprovedPaymentAndBeIdempotentOnRetry() throws Exception {
    LeadPayment payment = createPendingLeadPayment();
    String paymentId = "mp-payment-123";

    when(mercadoPagoService.fetchPayment(anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PaymentStatusResult(
            paymentId, "approved", String.valueOf(payment.getId()))));

    // Primera notificación: marca PAID.
    mockMvc.perform(post("/api/webhooks/mercadopago")
            .param("type", "payment")
            .param("data.id", paymentId))
        .andExpect(status().isOk());

    LeadPayment afterFirst = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(afterFirst.getCommissionStatus()).isEqualTo(CommissionStatus.PAID);
    assertThat(afterFirst.getPaidAt()).isNotNull();
    assertThat(afterFirst.getMpPaymentId()).isEqualTo(paymentId);
    java.time.OffsetDateTime firstPaidAt = afterFirst.getPaidAt();

    // Segunda notificación del mismo pago: idempotente, no reprocesa.
    mockMvc.perform(post("/api/webhooks/mercadopago")
            .param("type", "payment")
            .param("data.id", paymentId))
        .andExpect(status().isOk());

    LeadPayment afterSecond = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(afterSecond.getCommissionStatus()).isEqualTo(CommissionStatus.PAID);
    assertThat(afterSecond.getPaidAt()).isEqualTo(firstPaidAt);

    // El servicio de MP fue consultado en ambas notificaciones (webhook
    // siempre re-verifica), pero el efecto de negocio (marcar PAID) solo
    // ocurrió una vez, ya verificado arriba por paidAt sin cambios.
    verify(mercadoPagoService, times(2)).fetchPayment(anyString());
  }

  @Test
  void shouldEmitCommissionPaidEventExactlyOnceAcrossRetries() throws Exception {
    LeadPayment payment = createPendingLeadPayment();
    String paymentId = "mp-payment-789";

    when(mercadoPagoService.fetchPayment(anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PaymentStatusResult(
            paymentId, "approved", String.valueOf(payment.getId()))));

    mockMvc.perform(post("/api/webhooks/mercadopago")
            .param("type", "payment")
            .param("data.id", paymentId))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/webhooks/mercadopago")
            .param("type", "payment")
            .param("data.id", paymentId))
        .andExpect(status().isOk());

    long commissionPaidEvents = leadEventRepository
        .findByLeadIdOrderByCreatedAtAsc(payment.getLeadId()).stream()
        .filter(event -> "COMMISSION_PAID".equals(event.getType()))
        .count();
    assertThat(commissionPaidEvents).isEqualTo(1);
  }

  @Test
  void markPaidIfNotAlreadyTransitionsExactlyOnce() {
    // Guardia real contra el webhook duplicado casi simultáneo de MP (visto
    // en sandbox con 17 ms de diferencia): la transición es un UPDATE
    // condicional en la base — solo una invocación puede afectar la fila.
    LeadPayment payment = createPendingLeadPayment();
    java.time.OffsetDateTime now = java.time.OffsetDateTime.now();

    assertThat(leadPaymentRepository.markPaidIfNotAlready(
        payment.getId(), "mp-race-1", now, CommissionStatus.PAID)).isEqualTo(1);
    assertThat(leadPaymentRepository.markPaidIfNotAlready(
        payment.getId(), "mp-race-2", now.plusSeconds(1), CommissionStatus.PAID)).isEqualTo(0);

    LeadPayment reloaded = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getCommissionStatus()).isEqualTo(CommissionStatus.PAID);
    assertThat(reloaded.getMpPaymentId()).isEqualTo("mp-race-1");
  }

  @Test
  void shouldIgnoreNonApprovedStatus() throws Exception {
    LeadPayment payment = createPendingLeadPayment();
    String paymentId = "mp-payment-456";

    when(mercadoPagoService.fetchPayment(anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PaymentStatusResult(
            paymentId, "pending", String.valueOf(payment.getId()))));

    mockMvc.perform(post("/api/webhooks/mercadopago")
            .param("type", "payment")
            .param("data.id", paymentId))
        .andExpect(status().isOk());

    LeadPayment reloaded = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getCommissionStatus()).isEqualTo(CommissionStatus.PENDING);
    assertThat(reloaded.getPaidAt()).isNull();
  }

  @Test
  void shouldEmitCommissionSettledEventWhenOverdueCommissionGetsPaid() throws Exception {
    // Reactivación instantánea (FIXY_COBRANZAS.md): si la comisión que paga
    // estaba OVERDUE (matching pausado), la transición a PAID debe dejar
    // constancia de que el matching se reactivó.
    LeadPayment payment = createLeadPayment(CommissionStatus.OVERDUE);
    String paymentId = "mp-payment-overdue-1";

    when(mercadoPagoService.fetchPayment(anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PaymentStatusResult(
            paymentId, "approved", String.valueOf(payment.getId()))));

    mockMvc.perform(post("/api/webhooks/mercadopago")
            .param("type", "payment")
            .param("data.id", paymentId))
        .andExpect(status().isOk());

    LeadPayment reloaded = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getCommissionStatus()).isEqualTo(CommissionStatus.PAID);

    boolean settledEventEmitted = leadEventRepository
        .findByLeadIdOrderByCreatedAtAsc(payment.getLeadId()).stream()
        .anyMatch(event -> "COMMISSION_SETTLED".equals(event.getType()));
    assertThat(settledEventEmitted).isTrue();
  }
}
