package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.RemoteCarePlan;
import com.fixy.backend.model.RemoteCarePlanStatus;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.RemoteCarePlanRepository;
import com.fixy.backend.service.CustomerPaymentService;
import com.fixy.backend.service.MercadoPagoService;
import com.fixy.backend.service.RemoteCareBillingScheduler;
import com.fixy.backend.service.TelegramNotifyService;
import com.fixy.backend.service.WhatsAppService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Refundación de Fixy, fase 2 (contrato §B.3): facturación mensual del plan
 * Casa a distancia — planes ACTIVE vencidos (30+ días desde last_billed_at)
 * generan una nueva cuota; los que no vencieron o no están ACTIVE, no.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RemoteCareBillingSchedulerTest {

  @Autowired private RemoteCarePlanRepository remoteCarePlanRepository;
  @Autowired private CustomerPaymentRepository customerPaymentRepository;
  @Autowired private CustomerPaymentService customerPaymentService;
  @Autowired private TelegramNotifyService telegramNotifyService;
  @Autowired private WhatsAppService whatsAppService;

  @MockitoBean private MercadoPagoService mercadoPagoService;

  private RemoteCareBillingScheduler schedulerWithClock(Clock clock) {
    return new RemoteCareBillingScheduler(
        remoteCarePlanRepository, customerPaymentService, telegramNotifyService, whatsAppService, true, clock);
  }

  private RemoteCarePlan activePlan(String phone, OffsetDateTime lastBilledAt) {
    RemoteCarePlan plan = new RemoteCarePlan();
    plan.setOwnerName("Dueño Facturación");
    plan.setOwnerPhone(phone);
    plan.setPropertyZone("Solymar");
    plan.setPropertyAddress("Calle Facturación 1");
    plan.setMonthlyPrice(1900);
    plan.setStatus(RemoteCarePlanStatus.ACTIVE);
    plan.setAccessToken("token-" + phone);
    RemoteCarePlan saved = remoteCarePlanRepository.save(plan);
    saved.setLastBilledAt(lastBilledAt);
    return remoteCarePlanRepository.save(saved);
  }

  @Test
  void facturaPlanesVencidosYActualizaLastBilledAt() {
    when(mercadoPagoService.createPreference(anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(Optional.of(new MercadoPagoService.PreferenceResult("pref-bill", "https://mp.test/pref-bill")));

    OffsetDateTime now = OffsetDateTime.now();
    RemoteCarePlan due = activePlan("099720001", now.minusDays(31));
    RemoteCarePlan notDue = activePlan("099720002", now.minusDays(5));

    schedulerWithClock(Clock.systemUTC()).processOnce();

    assertThat(customerPaymentRepository.findByRemoteCarePlanIdOrderByCreatedAtDesc(due.getId()))
        .anyMatch(p -> p.getKind() == CustomerPaymentKind.PLAN_MONTHLY);
    assertThat(remoteCarePlanRepository.findById(due.getId()).orElseThrow().getLastBilledAt())
        .isAfter(now.minusMinutes(1));

    assertThat(customerPaymentRepository.findByRemoteCarePlanIdOrderByCreatedAtDesc(notDue.getId()))
        .isEmpty();
  }

  @Test
  void noFacturaPlanesPausadosOCancelados() {
    OffsetDateTime now = OffsetDateTime.now();
    RemoteCarePlan paused = activePlan("099720003", now.minusDays(40));
    paused.setStatus(RemoteCarePlanStatus.PAUSED);
    remoteCarePlanRepository.save(paused);

    schedulerWithClock(Clock.systemUTC()).processOnce();

    assertThat(customerPaymentRepository.findByRemoteCarePlanIdOrderByCreatedAtDesc(paused.getId())).isEmpty();
  }
}
