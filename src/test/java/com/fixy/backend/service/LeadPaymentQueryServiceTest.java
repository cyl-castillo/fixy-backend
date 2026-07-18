package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixy.backend.dto.LeadPaymentSummary;
import com.fixy.backend.dto.ProviderCommissionSummary;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit puro (sin contexto Spring) de summaryFor, en especial el corte de
 * "este mes" para earningsThisMonth (Ola 1 #2: ingresos propios del
 * proveedor, no solo la comisión que le debe a Fixy) y de markPaidManually
 * (panel admin, "marcar cobrada por transferencia").
 */
@ExtendWith(MockitoExtension.class)
class LeadPaymentQueryServiceTest {

  @Mock
  private LeadPaymentRepository leadPaymentRepository;

  @Mock
  private LeadRepository leadRepository;

  @Mock
  private LeadTimelineService leadTimelineService;

  @Mock
  private CommissionService commissionService;

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);

  private LeadPaymentQueryService service() {
    return new LeadPaymentQueryService(
        leadPaymentRepository, leadRepository, leadTimelineService, commissionService, FIXED_CLOCK);
  }

  private LeadPayment payment(BigDecimal amountCharged, BigDecimal commissionAmount,
      CommissionStatus status, OffsetDateTime createdAt) {
    LeadPayment p = new LeadPayment();
    p.setAmountCharged(amountCharged);
    p.setCommissionAmount(commissionAmount);
    p.setCommissionStatus(status);
    p.setCurrency("UYU");
    // createdAt es @Column(updatable=false) seteado por @PrePersist en JPA
    // real; en el test lo asignamos por reflexión de campo vía setter no
    // expuesto — se usa el mismo objeto simulando el estado persistido.
    setCreatedAt(p, createdAt);
    return p;
  }

  private void setCreatedAt(LeadPayment p, OffsetDateTime createdAt) {
    try {
      var field = LeadPayment.class.getDeclaredField("createdAt");
      field.setAccessible(true);
      field.set(p, createdAt);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void sinPagosDevuelveResumenVacio() {
    when(leadPaymentRepository.findByProviderIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

    var service = service();
    ProviderCommissionSummary summary = service.summaryFor(1L);

    assertThat(summary).isEqualTo(ProviderCommissionSummary.empty());
  }

  @Test
  void sumaAmountChargedComoIngresosPropiosDelMesActual() {
    OffsetDateTime thisMonth = OffsetDateTime.of(2026, 7, 10, 9, 0, 0, 0, ZoneOffset.UTC);
    OffsetDateTime lastMonth = OffsetDateTime.of(2026, 6, 20, 9, 0, 0, 0, ZoneOffset.UTC);
    List<LeadPayment> payments = List.of(
        payment(new BigDecimal("3000.00"), new BigDecimal("300.00"), CommissionStatus.PENDING, thisMonth),
        payment(new BigDecimal("1500.00"), new BigDecimal("150.00"), CommissionStatus.PAID, lastMonth)
    );
    when(leadPaymentRepository.findByProviderIdOrderByCreatedAtDesc(1L)).thenReturn(payments);

    var service = service();
    ProviderCommissionSummary summary = service.summaryFor(1L);

    // Ingresos: total incluye ambos leads, "este mes" solo el de julio.
    assertThat(summary.earningsTotal()).isEqualByComparingTo("4500.00");
    assertThat(summary.earningsTotalCount()).isEqualTo(2);
    assertThat(summary.earningsThisMonth()).isEqualByComparingTo("3000.00");
    assertThat(summary.earningsThisMonthCount()).isEqualTo(1);

    // Ingresos propios no deben confundirse con la comisión que le debe a
    // Fixy (mucho menor): el mensaje al proveedor es "ganaste", no "debés".
    assertThat(summary.pendingAmount()).isEqualByComparingTo("300.00");
    assertThat(summary.paidAmount()).isEqualByComparingTo("150.00");
  }

  @Test
  void comisionWaivedIgualSumaAIngresosPropios() {
    // WAIVED no cuenta para pending/paid (Fixy no cobra), pero el proveedor
    // sí cobró el trabajo igual — sus ingresos propios no dependen de si
    // Fixy decidió cobrarle o no.
    OffsetDateTime now = OffsetDateTime.of(2026, 7, 5, 9, 0, 0, 0, ZoneOffset.UTC);
    List<LeadPayment> payments = List.of(
        payment(new BigDecimal("2000.00"), new BigDecimal("200.00"), CommissionStatus.WAIVED, now)
    );
    when(leadPaymentRepository.findByProviderIdOrderByCreatedAtDesc(1L)).thenReturn(payments);

    var service = service();
    ProviderCommissionSummary summary = service.summaryFor(1L);

    assertThat(summary.pendingAmount()).isEqualByComparingTo("0");
    assertThat(summary.paidAmount()).isEqualByComparingTo("0");
    assertThat(summary.earningsThisMonth()).isEqualByComparingTo("2000.00");
    assertThat(summary.earningsTotal()).isEqualByComparingTo("2000.00");
  }

  private LeadPayment paymentWithId(Long id, Long leadId, CommissionStatus status) {
    LeadPayment p = payment(new BigDecimal("2500.00"), new BigDecimal("250.00"), status,
        OffsetDateTime.of(2026, 7, 15, 9, 0, 0, 0, ZoneOffset.UTC));
    p.setId(id);
    p.setLeadId(leadId);
    return p;
  }

  @Test
  void marcarPagadaManualmenteTransicionaYEmiteEvento() {
    LeadPayment before = paymentWithId(3L, 105L, CommissionStatus.PENDING);
    LeadPayment after = paymentWithId(3L, 105L, CommissionStatus.PAID);
    when(leadPaymentRepository.findById(3L)).thenReturn(Optional.of(before), Optional.of(after));
    when(leadPaymentRepository.markPaidIfNotAlready(eq(3L), anyString(), any(), eq(CommissionStatus.PAID)))
        .thenReturn(1);
    Lead lead = new Lead();
    when(leadRepository.findById(105L)).thenReturn(Optional.of(lead));

    LeadPaymentSummary result = service().markPaidManually(3L, "Nueva Era transferencia");

    assertThat(result.commissionStatus()).isEqualTo(CommissionStatus.PAID);
    verify(leadTimelineService).appendEvent(eq(lead), eq("COMMISSION_PAID"), eq("ops"), anyString());
  }

  @Test
  void marcarPagadaManualmenteReactivaSiEstabaOverdue() {
    // Reactivación instantánea (FIXY_COBRANZAS.md): la marca manual de ops
    // (transferencia) también reactiva el matching cuando la comisión que
    // se salda es la que lo tenía pausado.
    LeadPayment before = paymentWithId(3L, 105L, CommissionStatus.OVERDUE);
    LeadPayment after = paymentWithId(3L, 105L, CommissionStatus.PAID);
    when(leadPaymentRepository.findById(3L)).thenReturn(Optional.of(before), Optional.of(after));
    when(leadPaymentRepository.markPaidIfNotAlready(eq(3L), anyString(), any(), eq(CommissionStatus.PAID)))
        .thenReturn(1);
    Lead lead = new Lead();
    when(leadRepository.findById(105L)).thenReturn(Optional.of(lead));

    service().markPaidManually(3L, "Nueva Era transferencia");

    verify(commissionService).notifySettled(eq(before), eq(lead));
  }

  @Test
  void marcarPagadaManualmenteNoReactivaSiNoEstabaOverdue() {
    LeadPayment before = paymentWithId(3L, 105L, CommissionStatus.PENDING);
    LeadPayment after = paymentWithId(3L, 105L, CommissionStatus.PAID);
    when(leadPaymentRepository.findById(3L)).thenReturn(Optional.of(before), Optional.of(after));
    when(leadPaymentRepository.markPaidIfNotAlready(eq(3L), anyString(), any(), eq(CommissionStatus.PAID)))
        .thenReturn(1);
    Lead lead = new Lead();
    when(leadRepository.findById(105L)).thenReturn(Optional.of(lead));

    service().markPaidManually(3L, "Nueva Era transferencia");

    verify(commissionService, never()).notifySettled(any(), any());
  }

  @Test
  void marcarPagadaManualmenteEsIdempotenteSiYaEstabaPaid() {
    LeadPayment alreadyPaid = paymentWithId(3L, 105L, CommissionStatus.PAID);
    when(leadPaymentRepository.findById(3L)).thenReturn(Optional.of(alreadyPaid));
    // markPaidIfNotAlready devuelve 0 filas afectadas: ya estaba PAID.
    when(leadPaymentRepository.markPaidIfNotAlready(eq(3L), anyString(), any(), eq(CommissionStatus.PAID)))
        .thenReturn(0);

    LeadPaymentSummary result = service().markPaidManually(3L, "transferencia");

    assertThat(result.commissionStatus()).isEqualTo(CommissionStatus.PAID);
    verify(leadTimelineService, never()).appendEvent(any(), anyString(), anyString(), anyString());
  }

  @Test
  void marcarPagadaManualmenteLanza404SiNoExiste() {
    when(leadPaymentRepository.findById(99L)).thenReturn(Optional.empty());

    var service = service();
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.markPaidManually(99L, null));
  }
}
