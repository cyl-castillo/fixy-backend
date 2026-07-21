package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadMessageService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.MatchingStaleScheduler;
import com.fixy.backend.service.ProviderCatalogService;
import com.fixy.backend.service.PushNotificationService;
import com.fixy.backend.service.TelegramNotifyService;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * "Nunca más camino muerto": un lead listo para matching que nadie acepta
 * debe, tras el umbral, avisar UNA vez a cliente + proveedores + ops.
 *
 * Los eventos de timeline fijan su createdAt en @PrePersist (no se pueden
 * retro-datar), así que el paso del tiempo se simula construyendo el
 * scheduler con un Clock corrido al futuro — el constructor recibe Clock
 * justamente para esto (mismo patrón de diseño que los otros schedulers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.enabled=false"
})
class MatchingStaleSchedulerTest {

  private static final long THRESHOLD_MINUTES = 45;

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ProviderCatalogService providerCatalogService;
  @Autowired private LeadTimelineService timelineService;
  @Autowired private LeadMessageService messageService;
  @Autowired private PushNotificationService pushNotificationService;
  @Autowired private TelegramNotifyService telegramNotifyService;
  @Autowired private LeadMessageRepository leadMessageRepository;

  private MatchingStaleScheduler schedulerWithClock(Clock clock) {
    return new MatchingStaleScheduler(
        leadRepository, leadEventRepository, providerRepository, providerCatalogService,
        timelineService, messageService, pushNotificationService, telegramNotifyService,
        true, THRESHOLD_MINUTES, clock);
  }

  /** Clock corrido 1h al futuro: cualquier evento recién creado ya "cumplió" el umbral de 45 min. */
  private Clock inOneHour() {
    return Clock.fixed(Instant.now().plus(Duration.ofHours(1)), ZoneOffset.UTC);
  }

  private Lead createChatLead() throws Exception {
    MvcResult res = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer id = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    return leadRepository.findById(Long.valueOf(id)).orElseThrow();
  }

  private Lead makeReadyBroadcast(Lead lead) {
    lead.setDetectedCategory("plomeria");
    lead.setLocation("Solymar");
    lead.setReadyForMatching(true);
    Lead saved = leadRepository.save(lead);
    timelineService.appendEvent(saved, "MATCH_GENERATED", "system", "matching de test");
    return saved;
  }

  private long staleEventsFor(Long leadId) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, "MATCHING_STALE_NOTIFIED").size();
  }

  private long staleMessagesFor(Long leadId) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> m.getText() != null && m.getText().contains("demorando más de lo normal"))
        .count();
  }

  @Test
  void notificaUnaSolaVezAlSuperarElUmbral() throws Exception {
    Lead lead = makeReadyBroadcast(createChatLead());

    MatchingStaleScheduler scheduler = schedulerWithClock(inOneHour());
    scheduler.processOnce();

    assertThat(staleEventsFor(lead.getId())).isEqualTo(1);
    assertThat(staleMessagesFor(lead.getId())).isEqualTo(1);

    // Idempotencia: un segundo ciclo no duplica ni el evento ni el mensaje.
    scheduler.processOnce();
    assertThat(staleEventsFor(lead.getId())).isEqualTo(1);
    assertThat(staleMessagesFor(lead.getId())).isEqualTo(1);
  }

  @Test
  void noNotificaAntesDelUmbral() throws Exception {
    Lead lead = makeReadyBroadcast(createChatLead());

    // Clock real: el evento MATCH_GENERATED tiene segundos de vida, no 45 min.
    schedulerWithClock(Clock.systemUTC()).processOnce();

    assertThat(staleEventsFor(lead.getId())).isZero();
    assertThat(staleMessagesFor(lead.getId())).isZero();
  }

  @Test
  void ignoraLeadsTodaviaEnIntake() throws Exception {
    // Sin readyForMatching y sin eventos de matching: territorio del
    // ReengagementScheduler, no de este job — ni con el clock en el futuro.
    Lead lead = createChatLead();

    schedulerWithClock(inOneHour()).processOnce();

    assertThat(staleEventsFor(lead.getId())).isZero();
  }

  @Test
  void ignoraLeadsSmoke() throws Exception {
    Lead lead = createChatLead();
    lead.setProblem("[smoke] prueba sintética");
    makeReadyBroadcast(lead);

    schedulerWithClock(inOneHour()).processOnce();

    assertThat(staleEventsFor(lead.getId())).isZero();
  }
}
