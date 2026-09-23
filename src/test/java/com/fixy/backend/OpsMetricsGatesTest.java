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
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderOffer;
import com.fixy.backend.model.ProviderOfferContext;
import com.fixy.backend.model.ProviderOfferResponse;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRatingRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderOfferRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.OpsMetricsService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tier 3 (contrato §A.7): compuertas del plan de 90 días, true/false/null.
 * Contexto propio: semilla de proveedores apagada y una sola categoría
 * activa ("plomeria") para que "supplyOk" (que evalúa TODAS las categorías
 * activas) dependa solo de lo que este test crea, no de las 3 categorías
 * default ni de los 6 proveedores hardcodeados de ProviderSeedConfig.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.seed.providers=false",
    "fixy.orders.active-categories=plomeria"
})
@Transactional
class OpsMetricsGatesTest {

  @Autowired private OpsMetricsService opsMetricsService;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private CustomerPaymentRepository customerPaymentRepository;
  @Autowired private LeadRatingRepository leadRatingRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ProviderOfferRepository providerOfferRepository;
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

  private void addRating(Lead lead, int score) {
    LeadRating rating = new LeadRating();
    rating.setLeadId(lead.getId());
    rating.setProviderId(1L);
    rating.setScore(score);
    rating.setVerified(true);
    leadRatingRepository.saveAndFlush(rating);
  }

  private Provider createProvider(String name, ProviderStatus status) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("0" + Math.abs(name.hashCode() % 100000000));
    provider.setCategories("plomeria");
    provider.setStatus(status);
    return providerRepository.save(provider);
  }

  private void createOffer(Provider provider, Long leadId, OffsetDateTime offeredAt,
      OffsetDateTime respondedAt, ProviderOfferResponse response, boolean inWindow) {
    ProviderOffer offer = new ProviderOffer();
    offer.setLeadId(leadId);
    offer.setProviderId(provider.getId());
    offer.setContext(ProviderOfferContext.INITIAL);
    offer.setOfferedAt(offeredAt);
    offer.setRespondedAt(respondedAt);
    offer.setResponse(response);
    offer.setInWindow(inWindow);
    providerOfferRepository.save(offer);
  }

  private Lead realAcceptedLead(String phone, OffsetDateTime base, java.time.Duration acceptDelay) {
    Lead lead = createLead(phone, LeadStatus.ASSIGNED, base);
    lead.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(lead);
    appendCustomerMessage(lead);
    appendEvent(lead, "PROVIDER_CONTACTED", "system", "contactando", base.plusMinutes(1));
    appendEvent(lead, "PROVIDER_ACCEPTED", "provider", "acepto", base.plus(acceptDelay));
    return lead;
  }

  @Test
  void day30GatesAreTrueWhenAllThresholdsAreMet() {
    OffsetDateTime base = WINDOW_FROM.plusDays(1);

    // supplyOk: 5 proveedores activos en la única categoría activa (plomeria).
    for (int i = 0; i < 5; i++) {
      createProvider("Plomero Gate Supply " + i, ProviderStatus.AVAILABLE);
    }

    // fillRate2hOk + volumeOk: 10 pedidos reales, todos aceptados a los 30 min (<2h).
    for (int i = 0; i < 10; i++) {
      realAcceptedLead("09980" + (1000 + i), base, java.time.Duration.ofMinutes(30));
    }

    // responseOk: 3 ofertas en ventana respondidas rápido -> mediana 8 min (<15).
    Provider responder = createProvider("Plomero Gate Response", ProviderStatus.AVAILABLE);
    createOffer(responder, 8001L, base, base.plusMinutes(5), ProviderOfferResponse.ACCEPTED, true);
    createOffer(responder, 8002L, base, base.plusMinutes(8), ProviderOfferResponse.ACCEPTED, true);
    createOffer(responder, 8003L, base, base.plusMinutes(10), ProviderOfferResponse.ACCEPTED, true);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);
    OpsDailyMetricsResponse.Day30Gate day30 = metrics.gates().day30();

    assertThat(metrics.fillRate2hPercentage()).isEqualTo(100.0);
    assertThat(day30.fillRate2hOk()).isTrue();
    assertThat(metrics.medianOfferResponseMinutes()).isEqualTo(8);
    assertThat(day30.responseOk()).isTrue();
    assertThat(day30.supplyOk()).isTrue();
    assertThat(day30.volumeOk()).isTrue();
  }

  @Test
  void day30GatesAreFalseWhenBelowThresholds() {
    OffsetDateTime base = WINDOW_FROM.plusDays(1);

    // supplyOk falso: solo 2 proveedores activos declarados para esto (más
    // el de "responseOk" abajo, que también cae en plomeria) — igual < 5,
    // y sin ofertas atribuibles a la categoría (aceptación null).
    createProvider("Plomero Gate Supply Bajo 1", ProviderStatus.AVAILABLE);
    createProvider("Plomero Gate Supply Bajo 2", ProviderStatus.AVAILABLE);

    // fillRate2hOk + volumeOk falsos: 4 pedidos reales, solo 1 aceptado a
    // tiempo -> 25% (<70%) y 1 asignado (<10).
    realAcceptedLead("099810001", base, java.time.Duration.ofMinutes(30));
    realAcceptedLead("099810002", base, java.time.Duration.ofHours(3));
    Lead neverAccepted1 = createLead("099810003", LeadStatus.NEW, base);
    neverAccepted1.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(neverAccepted1);
    appendCustomerMessage(neverAccepted1);
    Lead neverAccepted2 = createLead("099810004", LeadStatus.NEW, base);
    neverAccepted2.setDetectedCategory("plomeria");
    leadRepository.saveAndFlush(neverAccepted2);
    appendCustomerMessage(neverAccepted2);

    // responseOk falso: mediana 30 min (>=15).
    Provider responder = createProvider("Plomero Gate Response Lento", ProviderStatus.AVAILABLE);
    createOffer(responder, 8101L, base, base.plusMinutes(30), ProviderOfferResponse.ACCEPTED, true);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);
    OpsDailyMetricsResponse.Day30Gate day30 = metrics.gates().day30();

    assertThat(metrics.fillRate2hPercentage()).isEqualTo(25.0);
    assertThat(day30.fillRate2hOk()).isFalse();
    assertThat(metrics.medianOfferResponseMinutes()).isEqualTo(30);
    assertThat(day30.responseOk()).isFalse();
    assertThat(day30.supplyOk()).isFalse();
    assertThat(day30.volumeOk()).isFalse();
  }

  @Test
  void day30GatesAreNullWithoutEnoughData() {
    // Sin pedidos reales ni ofertas: fillRate2hOk y responseOk deben ser
    // null (no "false" engañoso). volumeOk y supplyOk, en cambio, SÍ se
    // pueden evaluar con cero datos (dan false, no null) — la ausencia de
    // datos ahí significa "no se cumple", no "no se puede saber".
    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);
    OpsDailyMetricsResponse.Day30Gate day30 = metrics.gates().day30();

    assertThat(metrics.fillRate2hPercentage()).isNull();
    assertThat(day30.fillRate2hOk()).isNull();
    assertThat(metrics.medianOfferResponseMinutes()).isNull();
    assertThat(day30.responseOk()).isNull();
    assertThat(day30.volumeOk()).isFalse();
    assertThat(day30.supplyOk()).isFalse();
  }

  @Test
  void day60And90GatesReflectCollectionRepeatAndRatingThresholds() {
    OffsetDateTime base = WINDOW_FROM.plusDays(1);
    String[] phones = {"099820001", "099820002", "099820003", "099820004", "099820005"};
    List<Lead> completedLeads = new ArrayList<>();
    for (String phone : phones) {
      for (int i = 0; i < 4; i++) {
        completedLeads.add(createLead(phone, LeadStatus.COMPLETED, base));
      }
    }
    // 20 completados con 5 clientes distintos, todos repitiendo (4 cada
    // uno) -> repeatClients=5, repeatRate=100%.

    // Cobro: solo 2 de los 20 completados están pagados -> 10% (<15% -> kill).
    payServiceFee(completedLeads.get(0), CustomerPaymentStatus.PAID);
    payServiceFee(completedLeads.get(1), CustomerPaymentStatus.PAID);

    // Reseñas: 3 con score 5 -> promedio 5.0 (>=4.7).
    addRating(completedLeads.get(0), 5);
    addRating(completedLeads.get(1), 5);
    addRating(completedLeads.get(2), 5);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);
    OpsDailyMetricsResponse.Gates gates = metrics.gates();

    assertThat(gates.completed()).isEqualTo(20);
    assertThat(gates.day60().completedOk()).isTrue();

    assertThat(gates.paid()).isEqualTo(2);
    assertThat(gates.collectionPercentage()).isEqualTo(10.0);
    assertThat(gates.day60().collectionOk()).isFalse();
    assertThat(gates.day60().collectionKill()).isTrue();

    assertThat(gates.repeatClients()).isEqualTo(5);
    assertThat(gates.day60().repeatOk()).isTrue();

    // scaleOk: completed=20 < 25 -> false (determinístico, sin mirar fillRate2h).
    assertThat(gates.day90().scaleOk()).isFalse();

    assertThat(gates.repeatRatePercentage()).isEqualTo(100.0);
    assertThat(gates.day90().repeatRateOk()).isTrue();

    assertThat(gates.ratingAverage()).isEqualTo(5.0);
    assertThat(gates.day90().ratingOk()).isTrue();
  }

  @Test
  void day90RatingOkIsNullWithoutAnyReviewInRange() {
    // 20 completados (para no chocar con completedOk) pero sin ninguna reseña.
    for (int i = 0; i < 20; i++) {
      createLead("09983" + (1000 + i), LeadStatus.COMPLETED, WINDOW_FROM.plusDays(1));
    }

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);

    assertThat(metrics.gates().ratingAverage()).isNull();
    assertThat(metrics.gates().day90().ratingOk()).isNull();
  }
}
