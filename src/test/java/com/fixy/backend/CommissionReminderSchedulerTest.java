package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.CommissionReminderScheduler;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.PushNotificationService;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * H1.4: recordatorio amistoso de comisión a las 48h — el paso previo a la
 * escalada de los 7 días. Mismo truco de Clock corrido al futuro que
 * MatchingStaleSchedulerTest (los createdAt no se pueden retro-datar).
 * Umbral efectivo en los tests: 96h (48×2) porque toda comisión creada acá
 * es la primera de su proveedor.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommissionReminderSchedulerTest {

  private static final long REMINDER_HOURS = 48;

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadPaymentRepository leadPaymentRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadTimelineService timelineService;
  @Autowired private LeadMessageService messageService;
  @Autowired private PushNotificationService pushNotificationService;
  @Autowired private LeadMessageRepository leadMessageRepository;

  private CommissionReminderScheduler schedulerWithClock(Clock clock) {
    return new CommissionReminderScheduler(
        leadPaymentRepository, leadRepository, providerRepository, timelineService,
        messageService, pushNotificationService, true, REMINDER_HOURS, clock);
  }

  /** 5 días al futuro: supera el umbral doble (96h) de primera comisión. */
  private Clock inFiveDays() {
    return Clock.fixed(Instant.now().plus(Duration.ofDays(5)), ZoneOffset.UTC);
  }

  private record Ctx(Lead lead, LeadPayment payment) {
  }

  private Ctx createPendingCommission(String problem) throws Exception {
    MvcResult leadRes = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
    Lead lead = leadRepository.findById(Long.valueOf(leadId)).orElseThrow();
    lead.setProblem(problem);
    lead.setDetectedCategory("plomeria");
    lead = leadRepository.save(lead);

    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Proveedor Reminder Test %d",
                  "phone": "09972%04d",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "plomeria"
                }
                """.formatted(leadId, leadId % 10000)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");
    Provider provider = providerRepository.findById(Long.valueOf(providerId)).orElseThrow();

    LeadPayment payment = new LeadPayment();
    payment.setLeadId(lead.getId());
    payment.setProviderId(provider.getId());
    payment.setAmountCharged(new BigDecimal("2500"));
    payment.setCommissionRate(new BigDecimal("0.10"));
    payment.setCommissionAmount(new BigDecimal("250"));
    payment.setCurrency("UYU");
    payment.setCommissionStatus(CommissionStatus.PENDING);
    payment = leadPaymentRepository.save(payment);

    return new Ctx(lead, payment);
  }

  private long remindedEventsFor(Long leadId) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, "COMMISSION_REMINDED").size();
  }

  @Test
  void recuerdaUnaSolaVezYSoloAlProveedor() throws Exception {
    Ctx ctx = createPendingCommission("pérdida de agua, prueba reminder");

    CommissionReminderScheduler scheduler = schedulerWithClock(inFiveDays());
    scheduler.processOnce();

    assertThat(remindedEventsFor(ctx.lead().getId())).isEqualTo(1);
    // El mensaje va provider_only: existe en el chat pero con audiencia proveedor.
    var reminder = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(ctx.lead().getId()).stream()
        .filter(m -> m.getText() != null && m.getText().contains("sigue pendiente"))
        .findFirst()
        .orElseThrow();
    assertThat(reminder.getAudience()).isEqualTo("provider_only");

    // Idempotencia: segundo ciclo no duplica.
    scheduler.processOnce();
    assertThat(remindedEventsFor(ctx.lead().getId())).isEqualTo(1);
  }

  @Test
  void noRecuerdaAntesDelUmbral() throws Exception {
    Ctx ctx = createPendingCommission("recién completado, prueba reminder");

    schedulerWithClock(Clock.systemUTC()).processOnce();

    assertThat(remindedEventsFor(ctx.lead().getId())).isZero();
  }

  @Test
  void ignoraSmokeYPagadas() throws Exception {
    Ctx smoke = createPendingCommission("[smoke] prueba sintética reminder");
    Ctx paid = createPendingCommission("trabajo ya pagado, prueba reminder");
    paid.payment().setCommissionStatus(CommissionStatus.PAID);
    leadPaymentRepository.save(paid.payment());

    schedulerWithClock(inFiveDays()).processOnce();

    assertThat(remindedEventsFor(smoke.lead().getId())).isZero();
    assertThat(remindedEventsFor(paid.lead().getId())).isZero();
  }
}
