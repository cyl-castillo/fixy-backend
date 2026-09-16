package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.CustomerPaymentQueryService;
import com.fixy.backend.service.CustomerPaymentReminderScheduler;
import com.fixy.backend.service.LeadMessageService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Contrato §A.4.5: recordatorio único del cargo de servicio — cada hora,
 * SERVICE_FEE PENDING con link, más de 48h sin recordar, un solo mensaje.
 * Nunca cambia el status (doctrina: "nunca castigar por no pagar").
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerPaymentReminderSchedulerTest {

  @Autowired private CustomerPaymentRepository customerPaymentRepository;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private CustomerPaymentQueryService customerPaymentQueryService;
  @Autowired private LeadMessageService leadMessageService;

  private CustomerPaymentReminderScheduler schedulerWithClock(Clock clock) {
    return new CustomerPaymentReminderScheduler(customerPaymentQueryService, leadMessageService,
        customerPaymentRepository, leadRepository, telegramNotifyService, true, clock);
  }

  @org.springframework.beans.factory.annotation.Autowired private com.fixy.backend.service.TelegramNotifyService telegramNotifyService;

  @Test
  void avisaAOpsUnaVezCuandoElCargoLleva7DiasSinPagar() {
    Lead lead = createLead("099730077");
    CustomerPayment payment = createServiceFee(lead, "https://mp.test/pref-unpaid");
    com.fixy.backend.service.TelegramNotifyService telegram =
        org.mockito.Mockito.mock(com.fixy.backend.service.TelegramNotifyService.class);
    CustomerPaymentReminderScheduler scheduler = new CustomerPaymentReminderScheduler(
        customerPaymentQueryService, leadMessageService, customerPaymentRepository, leadRepository, telegram,
        true, inFuture(Duration.ofDays(8)));

    scheduler.processOnce();

    org.mockito.Mockito.verify(telegram, org.mockito.Mockito.times(1))
        .notifyServiceFeeUnpaid(org.mockito.ArgumentMatchers.argThat(l -> l.getId().equals(lead.getId())),
            org.mockito.ArgumentMatchers.argThat(a -> a.compareTo(payment.getAmount()) == 0),
            org.mockito.ArgumentMatchers.eq(payment.getId()), org.mockito.ArgumentMatchers.eq(7L));
    // El cargo NO cambia: nunca castigar por no pagar.
    assertThat(customerPaymentRepository.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(CustomerPaymentStatus.PENDING);
  }

  @Test
  void noAvisaAOpsAntesDeLos7Dias() {
    Lead lead = createLead("099730078");
    createServiceFee(lead, "https://mp.test/pref-fresh");
    com.fixy.backend.service.TelegramNotifyService telegram =
        org.mockito.Mockito.mock(com.fixy.backend.service.TelegramNotifyService.class);
    CustomerPaymentReminderScheduler scheduler = new CustomerPaymentReminderScheduler(
        customerPaymentQueryService, leadMessageService, customerPaymentRepository, leadRepository, telegram,
        true, inFuture(Duration.ofDays(2)));

    scheduler.processOnce();

    org.mockito.Mockito.verify(telegram, org.mockito.Mockito.never())
        .notifyServiceFeeUnpaid(org.mockito.ArgumentMatchers.argThat(l -> l.getId().equals(lead.getId())),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
  }

  private Clock inFuture(Duration duration) {
    return Clock.fixed(Instant.now().plus(duration), ZoneOffset.UTC);
  }

  private Lead createLead(String phone) {
    Lead lead = new Lead();
    lead.setName("Cliente Recordatorio");
    lead.setPhone(phone);
    lead.setProblem("Problema de prueba recordatorio");
    lead.setChannel("web-order");
    lead.setStatus(LeadStatus.COMPLETED);
    lead.setAccessToken("token-" + phone);
    return leadRepository.save(lead);
  }

  private CustomerPayment createServiceFee(Lead lead, String link) {
    CustomerPayment payment = new CustomerPayment();
    payment.setKind(CustomerPaymentKind.SERVICE_FEE);
    payment.setLeadId(lead.getId());
    payment.setAmount(new java.math.BigDecimal("525"));
    payment.setStatus(CustomerPaymentStatus.PENDING);
    payment.setMpPaymentLink(link);
    return customerPaymentRepository.save(payment);
  }

  @Test
  void recuerdaUnaSolaVezPasadas48hConLink() {
    Lead lead = createLead("099730001");
    CustomerPayment payment = createServiceFee(lead, "https://mp.test/pref-reminder");

    CustomerPaymentReminderScheduler scheduler = schedulerWithClock(inFuture(Duration.ofHours(50)));
    scheduler.processOnce();

    CustomerPayment reloaded = customerPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getRemindedAt()).isNotNull();
    assertThat(reloaded.getStatus()).isEqualTo(CustomerPaymentStatus.PENDING);

    long reminders = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .filter(m -> m.getText() != null && m.getText().contains("¿Te acordás del servicio Fixy?"))
        .count();
    assertThat(reminders).isEqualTo(1);

    // Segundo ciclo: idempotente, no duplica.
    scheduler.processOnce();
    long remindersAfterSecondRun = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .filter(m -> m.getText() != null && m.getText().contains("¿Te acordás del servicio Fixy?"))
        .count();
    assertThat(remindersAfterSecondRun).isEqualTo(1);
  }

  @Test
  void noRecuerdaAntesDe48h() {
    Lead lead = createLead("099730002");
    CustomerPayment payment = createServiceFee(lead, "https://mp.test/pref-reminder-2");

    schedulerWithClock(Clock.systemUTC()).processOnce();

    CustomerPayment reloaded = customerPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getRemindedAt()).isNull();
  }

  @Test
  void noRecuerdaSinLink() {
    Lead lead = createLead("099730003");
    CustomerPayment payment = createServiceFee(lead, null);

    schedulerWithClock(inFuture(Duration.ofHours(50))).processOnce();

    CustomerPayment reloaded = customerPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getRemindedAt()).isNull();
  }
}
