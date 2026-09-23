package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.ReviewRequestScheduler;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tier 2 (contrato §C.2): pedido de reseña a las {@code after-hours} del
 * pago del cargo de servicio (o del COMPLETED si no pagó). {@code
 * after-hours=0} fuerza que cualquier COMPLETED ya cumpla el umbral al
 * correr el job — mismo truco que {@code LeadClosingSchedulerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.payments.provider-commission-enabled=false",
    "fixy.reviews.request.after-hours=0"
})
class ReviewRequestSchedulerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ReviewRequestScheduler scheduler;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private LeadRatingRepository leadRatingRepository;
  @Autowired private CustomerPaymentRepository customerPaymentRepository;
  @Autowired private com.fixy.backend.repository.LeadEventRepository leadEventRepository;
  @Autowired private com.fixy.backend.repository.ProviderRepository providerRepository;
  @Autowired private com.fixy.backend.service.LeadTimelineService timelineService;
  @Autowired private com.fixy.backend.service.LeadMessageService messageService;
  @Autowired private com.fixy.backend.service.PushNotificationService pushNotificationService;

  /** Scheduler a mano con reloj corrido (mismo truco que MatchingWatchdogSchedulerTest). */
  private ReviewRequestScheduler schedulerWithClock(java.time.Clock clock, long afterHours, long maxAgeDays) {
    return new ReviewRequestScheduler(leadRepository, leadEventRepository, leadRatingRepository,
        customerPaymentRepository, providerRepository, timelineService, messageService, pushNotificationService,
        true, afterHours, maxAgeDays, "https://www.fixy.com.uy", clock);
  }

  private record ProviderAndLead(Integer providerId, String providerToken, Integer leadId, String leadToken) {
  }

  private ProviderAndLead createCompletedLead(String providerPhone, String leadPhone, String problem) throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Tecnico Review Test", "phone": "%s", "primaryZone": "Solymar",
                 "city": "Ciudad de la Costa", "categories": "gas"}
                """.formatted(providerPhone)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");

    MvcResult tk = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String providerToken = JsonPath.read(tk.getResponse().getContentAsString(), "$.accessToken");

    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"phone": "%s", "problem": "%s", "channel": "web-app",
                 "serviceCategory": "gas", "zone": "Solymar"}
                """.formatted(leadPhone, problem)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
    String leadToken = leadRepository.findById(leadId.longValue()).orElseThrow().getAccessToken();

    mockMvc.perform(patch("/api/leads/{id}", leadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"ASSIGNED\", \"assignedProviderId\": %d}".formatted(providerId)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"COMPLETED\"}"))
        .andExpect(status().isOk());

    return new ProviderAndLead(providerId, providerToken, leadId, leadToken);
  }

  private List<LeadMessage> messagesFor(Integer leadId) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId.longValue());
  }

  @Test
  void mandaElPedidoDeResenaUnaVezYNuncaDos() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099850001", "099850101", "Pérdida de gas para test de reseña");

    int processed = scheduler.processOnce();
    assertThat(processed).isEqualTo(1);

    assertThat(messagesFor(ctx.leadId())).extracting(LeadMessage::getText)
        .anyMatch(t -> t.contains("Dejá tu reseña en 30 segundos") && t.contains("/r/" + ctx.leadId() + "/"));

    // Idempotente.
    int processedAgain = scheduler.processOnce();
    assertThat(processedAgain).isEqualTo(0);
  }

  @Test
  void noMandaNadaSiYaTieneReseñaPropia() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099850002", "099850102", "Pérdida de gas dos");

    mockMvc.perform(post("/api/public/leads/{id}/confirm-completion", ctx.leadId())
            .param("token", ctx.leadToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"confirmed\": true, \"score\": 5}"))
        .andExpect(status().isOk());

    int processed = scheduler.processOnce();
    assertThat(processed).isEqualTo(0);
  }

  @Test
  void avisaConSelloVerificadoSiElCargoDeServicioYaEstaPagado() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099850003", "099850103", "Pérdida de gas tres");

    CustomerPayment payment = new CustomerPayment();
    payment.setKind(CustomerPaymentKind.SERVICE_FEE);
    payment.setLeadId(ctx.leadId().longValue());
    payment.setBaseAmount(BigDecimal.valueOf(1000));
    payment.setAmount(BigDecimal.valueOf(150));
    payment.setStatus(CustomerPaymentStatus.PAID);
    payment.setPaidAt(OffsetDateTime.now());
    customerPaymentRepository.save(payment);

    int processed = scheduler.processOnce();
    assertThat(processed).isEqualTo(1);

    assertThat(messagesFor(ctx.leadId())).extracting(LeadMessage::getText)
        .anyMatch(t -> t.contains("reseña verificada"));
  }

  @Test
  void ignoraElTraficoSmokeAunqueEsteCompleted() throws Exception {
    ProviderAndLead ctx = createCompletedLead("099850004", "099850104", "[smoke] pedido sintetico");

    int processed = scheduler.processOnce();
    assertThat(processed).isEqualTo(0);
    assertThat(messagesFor(ctx.leadId())).extracting(LeadMessage::getText)
        .noneMatch(t -> t.contains("Dejá tu reseña"));
  }

  @Test
  void noPideResenaDeUnTrabajoViejoYLoMarcaComoSalteado() throws Exception {
    // El bug del arranque del 2026-09-21: pidió reseña a trabajos de agosto.
    // Reloj 8 días en el futuro con max-age-days=7 → REVIEW_REQUEST_SKIPPED,
    // sin mensaje, y no se vuelve a evaluar.
    ProviderAndLead ctx = createCompletedLead("099850005", "099850105", "Trabajo viejo para test de reseña");
    java.time.Clock future = java.time.Clock.fixed(java.time.Instant.now().plus(java.time.Duration.ofDays(8)),
        java.time.ZoneOffset.UTC);
    ReviewRequestScheduler old = schedulerWithClock(future, 0, 7);

    assertThat(old.processOnce()).isEqualTo(0);
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(ctx.leadId().longValue(), "REVIEW_REQUEST_SKIPPED"))
        .hasSize(1);
    assertThat(messagesFor(ctx.leadId())).extracting(LeadMessage::getText)
        .noneMatch(t -> t.contains("Dejá tu reseña"));

    // Ni el scheduler real (reloj de hoy) lo vuelve a tocar: quedó marcado.
    scheduler.processOnce();
    assertThat(messagesFor(ctx.leadId())).extracting(LeadMessage::getText)
        .noneMatch(t -> t.contains("Dejá tu reseña"));
  }
}
