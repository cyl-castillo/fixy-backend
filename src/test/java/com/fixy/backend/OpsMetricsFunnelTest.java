package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.dto.OpsDailyMetricsResponse;
import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.CustomerPaymentKind;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadRating;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.OpsMetricsService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tier 3 (contrato §A.1/§A.2): embudo "del chat al cobro" y fillRate2h.
 * Semilla de proveedores apagada (contexto propio, sin los 6 proveedores
 * hardcodeados de ProviderSeedConfig) para no depender de datos externos al
 * verificar el embudo — este test no ejercita proveedores directamente.
 */
@SpringBootTest
@TestPropertySource(properties = "fixy.seed.providers=false")
@Transactional
class OpsMetricsFunnelTest {

  @Autowired private OpsMetricsService opsMetricsService;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private CustomerPaymentRepository customerPaymentRepository;
  @Autowired private LeadRatingRepository leadRatingRepository;
  @Autowired private EntityManager entityManager;

  private static final OffsetDateTime WINDOW_FROM = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  private static final OffsetDateTime WINDOW_TO = OffsetDateTime.of(2026, 6, 8, 0, 0, 0, 0, ZoneOffset.UTC);

  private Lead createLead(String phone, LeadStatus status, OffsetDateTime createdAt) {
    Lead lead = new Lead();
    lead.setProblem("Problema de prueba");
    lead.setPhone(phone);
    lead.setChannel("whatsapp");
    lead.setStatus(status);
    lead = leadRepository.saveAndFlush(lead);
    forceCreatedAt(lead.getId(), createdAt);
    entityManager.clear();
    return leadRepository.findById(lead.getId()).orElseThrow();
  }

  private void forceCreatedAt(Long leadId, OffsetDateTime createdAt) {
    entityManager.createNativeQuery("UPDATE leads SET created_at = ?1, updated_at = ?1 WHERE id = ?2")
        .setParameter(1, createdAt)
        .setParameter(2, leadId)
        .executeUpdate();
  }

  private void appendEvent(Lead lead, String type, String actor, String message, OffsetDateTime createdAt) {
    LeadEvent event = new LeadEvent();
    event.setLead(lead);
    event.setType(type);
    event.setActor(actor);
    event.setMessage(message);
    event = leadEventRepository.saveAndFlush(event);
    entityManager.createNativeQuery("UPDATE lead_events SET created_at = ?1 WHERE id = ?2")
        .setParameter(1, createdAt)
        .setParameter(2, event.getId())
        .executeUpdate();
    entityManager.clear();
  }

  private void appendCustomerMessage(Lead lead) {
    LeadMessage message = new LeadMessage();
    message.setLeadId(lead.getId());
    message.setSender("customer");
    message.setText("hola, necesito ayuda");
    leadMessageRepository.saveAndFlush(message);
  }

  private void payServiceFee(Lead lead, CustomerPaymentStatus status) {
    CustomerPayment payment = new CustomerPayment();
    payment.setKind(CustomerPaymentKind.SERVICE_FEE);
    payment.setLeadId(lead.getId());
    payment.setAmount(BigDecimal.valueOf(500));
    payment.setStatus(status);
    if (status == CustomerPaymentStatus.PAID) {
      payment.setPaidAt(OffsetDateTime.now());
    }
    customerPaymentRepository.saveAndFlush(payment);
  }

  private void addRating(Lead lead, int score, boolean verified) {
    LeadRating rating = new LeadRating();
    rating.setLeadId(lead.getId());
    rating.setProviderId(1L);
    rating.setScore(score);
    rating.setVerified(verified);
    leadRatingRepository.saveAndFlush(rating);
  }

  @Test
  void funnelCountsEachStageIndependentlyForLeadsInRange() {
    OffsetDateTime base = WINDOW_FROM.plusDays(1);

    // "chats": todo lead creado en el rango cuenta, incluso el que nunca arrancó.
    createLead("099700001", LeadStatus.NEW, base);

    // "real" sin llegar a "contacted": categoría + mensaje del cliente, nada más.
    Lead realNotContacted = createLead("099700002", LeadStatus.NEW, base);
    realNotContacted.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(realNotContacted);
    appendCustomerMessage(realNotContacted);

    // "contacted" sin llegar a "assigned": PROVIDER_CONTACTED, nunca aceptado.
    Lead contactedOnly = createLead("099700003", LeadStatus.PROVIDER_CONTACTED, base);
    contactedOnly.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(contactedOnly);
    appendCustomerMessage(contactedOnly);
    appendEvent(contactedOnly, "PROVIDER_CONTACTED", "system", "contactando", base.plusMinutes(1));

    // "assigned": PROVIDER_ACCEPTED tras el contacto.
    Lead assignedLead = createLead("099700004", LeadStatus.ASSIGNED, base);
    assignedLead.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(assignedLead);
    appendCustomerMessage(assignedLead);
    appendEvent(assignedLead, "PROVIDER_CONTACTED", "system", "contactando", base.plusMinutes(1));
    appendEvent(assignedLead, "PROVIDER_ACCEPTED", "provider", "acepto", base.plusMinutes(5));

    // "completed": cerrado, sin cobro ni reseña todavía. Su status COMPLETED
    // ya lo hace contar también como "assigned" (status vigente ASSIGNED+).
    Lead completedLead = createLead("099700005", LeadStatus.COMPLETED, base);
    completedLead.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(completedLead);
    appendCustomerMessage(completedLead);

    // "paid": completado y con el cargo de servicio cobrado.
    Lead paidLead = createLead("099700006", LeadStatus.COMPLETED, base);
    paidLead.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(paidLead);
    appendCustomerMessage(paidLead);
    payServiceFee(paidLead, CustomerPaymentStatus.PAID);

    // "reviewed" + "verifiedReviews": cobrado y con reseña verificada.
    Lead reviewedLead = createLead("099700007", LeadStatus.COMPLETED, base);
    reviewedLead.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(reviewedLead);
    appendCustomerMessage(reviewedLead);
    payServiceFee(reviewedLead, CustomerPaymentStatus.PAID);
    addRating(reviewedLead, 5, true);

    // "reviewed" pero NO "verifiedReviews": reseña sin verificar.
    Lead reviewedUnverifiedLead = createLead("099700008", LeadStatus.COMPLETED, base);
    reviewedUnverifiedLead.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(reviewedUnverifiedLead);
    appendCustomerMessage(reviewedUnverifiedLead);
    addRating(reviewedUnverifiedLead, 4, false);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);
    OpsDailyMetricsResponse.Funnel funnel = metrics.funnel();

    assertThat(metrics.totalLeadsCreated()).isEqualTo(8);
    assertThat(funnel.chats()).isEqualTo(8);
    // real: todos menos el primero (7).
    assertThat(funnel.real()).isEqualTo(7);
    assertThat(metrics.realRequests()).isEqualTo(7);
    // contacted: contactedOnly + assignedLead (2) — el resto no tiene ni
    // oferta ni evento PROVIDER_CONTACTED.
    assertThat(funnel.contacted()).isEqualTo(2);
    // assigned: assignedLead (evento) + los 4 COMPLETED (status ASSIGNED+) = 5.
    assertThat(funnel.assigned()).isEqualTo(5);
    // completed: los 4 leads en status COMPLETED.
    assertThat(funnel.completed()).isEqualTo(4);
    assertThat(metrics.completedJobs()).isEqualTo(4);
    // paid: paidLead + reviewedLead (2).
    assertThat(funnel.paid()).isEqualTo(2);
    // reviewed: reviewedLead + reviewedUnverifiedLead (2).
    assertThat(funnel.reviewed()).isEqualTo(2);
    // verifiedReviews: solo reviewedLead (1).
    assertThat(funnel.verifiedReviews()).isEqualTo(1);
  }

  @Test
  void funnelExcludesSmokeLeadsFromEveryStage() {
    OffsetDateTime base = WINDOW_FROM.plusDays(1);
    Lead smoke = createLead("099730001", LeadStatus.COMPLETED, base);
    smoke.setProblem("[smoke] prueba automatizada");
    smoke.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(smoke);
    appendCustomerMessage(smoke);
    payServiceFee(smoke, CustomerPaymentStatus.PAID);
    addRating(smoke, 5, true);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);
    OpsDailyMetricsResponse.Funnel funnel = metrics.funnel();

    assertThat(funnel.chats()).isZero();
    assertThat(funnel.real()).isZero();
    assertThat(funnel.completed()).isZero();
    assertThat(funnel.paid()).isZero();
    assertThat(funnel.reviewed()).isZero();
    assertThat(funnel.verifiedReviews()).isZero();
  }

  @Test
  void fillRate2hCountsOnlyRealLeadsAcceptedWithinTwoHours() {
    OffsetDateTime base = WINDOW_FROM.plusDays(2);

    // Real, aceptado a los 90 min -> dentro de las 2h.
    Lead withinWindow = createLead("099710001", LeadStatus.ASSIGNED, base);
    withinWindow.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(withinWindow);
    appendCustomerMessage(withinWindow);
    appendEvent(withinWindow, "PROVIDER_CONTACTED", "system", "contactando", base.plusMinutes(5));
    appendEvent(withinWindow, "PROVIDER_ACCEPTED", "provider", "acepto", base.plusMinutes(90));

    // Real, aceptado a las 3h -> fuera de las 2h.
    Lead outsideWindow = createLead("099710002", LeadStatus.ASSIGNED, base);
    outsideWindow.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(outsideWindow);
    appendCustomerMessage(outsideWindow);
    appendEvent(outsideWindow, "PROVIDER_CONTACTED", "system", "contactando", base.plusMinutes(5));
    appendEvent(outsideWindow, "PROVIDER_ACCEPTED", "provider", "acepto", base.plusHours(3));

    // Real, nunca aceptado -> no cuenta como lleno.
    Lead neverAccepted = createLead("099710003", LeadStatus.NEW, base);
    neverAccepted.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(neverAccepted);
    appendCustomerMessage(neverAccepted);

    // NO real (sin categoría ni mensaje) aunque esté ASSIGNED -> no debe
    // afectar ni el numerador ni el denominador de fillRate2h.
    createLead("099710004", LeadStatus.ASSIGNED, base);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);

    // 1 de 3 pedidos reales dentro de las 2h -> 100/3 %.
    assertThat(metrics.fillRate2hPercentage()).isEqualTo(100.0 / 3);
  }

  @Test
  void fillRate2hIsNullWhenThereAreNoRealLeadsInRange() {
    createLead("099720001", LeadStatus.NEW, WINDOW_FROM.plusDays(1));

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);

    assertThat(metrics.fillRate2hPercentage()).isNull();
  }
}
