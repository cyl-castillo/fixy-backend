package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fixy.backend.dto.ProviderCommissionSummary;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.repository.LeadPaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit puro (sin contexto Spring) de summaryFor, en especial el corte de
 * "este mes" para earningsThisMonth (Ola 1 #2: ingresos propios del
 * proveedor, no solo la comisión que le debe a Fixy).
 */
@ExtendWith(MockitoExtension.class)
class LeadPaymentQueryServiceTest {

  @Mock
  private LeadPaymentRepository leadPaymentRepository;

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);

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

    var service = new LeadPaymentQueryService(leadPaymentRepository, FIXED_CLOCK);
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

    var service = new LeadPaymentQueryService(leadPaymentRepository, FIXED_CLOCK);
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

    var service = new LeadPaymentQueryService(leadPaymentRepository, FIXED_CLOCK);
    ProviderCommissionSummary summary = service.summaryFor(1L);

    assertThat(summary.pendingAmount()).isEqualByComparingTo("0");
    assertThat(summary.paidAmount()).isEqualByComparingTo("0");
    assertThat(summary.earningsThisMonth()).isEqualByComparingTo("2000.00");
    assertThat(summary.earningsTotal()).isEqualByComparingTo("2000.00");
  }
}
