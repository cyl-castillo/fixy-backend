package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.dto.OpsDailyMetricsResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.OpsMetricsService;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class OpsMetricsServiceTest {

  @Autowired
  private OpsMetricsService opsMetricsService;

  @Autowired
  private LeadRepository leadRepository;

  @Autowired
  private LeadEventRepository leadEventRepository;

  @Autowired
  private EntityManager entityManager;

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

  private void appendEvent(Lead lead, String type, OffsetDateTime createdAt) {
    LeadEvent event = new LeadEvent();
    event.setLead(lead);
    event.setType(type);
    event.setActor("system");
    event.setMessage("evento de prueba " + type);
    event = leadEventRepository.saveAndFlush(event);
    entityManager.createNativeQuery("UPDATE lead_events SET created_at = ?1 WHERE id = ?2")
        .setParameter(1, createdAt)
        .setParameter(2, event.getId())
        .executeUpdate();
    entityManager.clear();
  }

  @Test
  void computesExactFillRateFromCurrentStatus() {
    // 5 leads creados en la ventana: 3 en status "llenado" (ASSIGNED/IN_PROGRESS/COMPLETED),
    // 2 en status "no llenado" (NEW, CANCELLED) -> fill rate = 3/5 = 60%.
    createLead("099111001", LeadStatus.ASSIGNED, WINDOW_FROM.plusDays(1));
    createLead("099111002", LeadStatus.IN_PROGRESS, WINDOW_FROM.plusDays(1));
    createLead("099111003", LeadStatus.COMPLETED, WINDOW_FROM.plusDays(1));
    createLead("099111004", LeadStatus.NEW, WINDOW_FROM.plusDays(1));
    createLead("099111005", LeadStatus.CANCELLED, WINDOW_FROM.plusDays(1));

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);

    assertThat(metrics.totalLeadsCreated()).isEqualTo(5);
    assertThat(metrics.fillRatePercentage()).isEqualTo(60.0);
    assertThat(metrics.leadsByStatus().get("ASSIGNED")).isEqualTo(1L);
    assertThat(metrics.leadsByStatus().get("IN_PROGRESS")).isEqualTo(1L);
    assertThat(metrics.leadsByStatus().get("COMPLETED")).isEqualTo(1L);
    assertThat(metrics.leadsByStatus().get("NEW")).isEqualTo(1L);
    assertThat(metrics.leadsByStatus().get("CANCELLED")).isEqualTo(1L);
  }

  @Test
  void computesMedianUsingFirstContactAndFirstResponseAfterIt_evenWithMultipleSequentialContacts() {
    OffsetDateTime base = WINDOW_FROM.plusDays(1);

    // Lead A: contacto único a las 10:00, acepta a las 10:05 -> 300s.
    Lead leadA = createLead("099222001", LeadStatus.ASSIGNED, base);
    appendEvent(leadA, "PROVIDER_CONTACTED", base.plusMinutes(0));
    appendEvent(leadA, "PROVIDER_ACCEPTED", base.plusMinutes(5));

    // Lead B: múltiples PROVIDER_CONTACTED secuenciales (proveedor 1 no responde,
    // se contacta proveedor 2). El tiempo de respuesta debe medirse desde el
    // PRIMER contacto (t=0) hasta la PRIMERA respuesta posterior a ese primer
    // contacto (el reject a los 20 min), NO desde el segundo contacto.
    Lead leadB = createLead("099222002", LeadStatus.ASSIGNED, base);
    appendEvent(leadB, "PROVIDER_CONTACTED", base.plusMinutes(0));  // primer contacto
    appendEvent(leadB, "PROVIDER_CONTACTED", base.plusMinutes(10)); // segundo proveedor contactado
    appendEvent(leadB, "PROVIDER_REJECTED", base.plusMinutes(20));  // primera respuesta tras el primer contacto
    // Tiempo esperado para B: 20 min = 1200s (desde primer contacto en t=0, no desde t=10min).

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);

    // Mediana de [300, 1200] (2 valores) = (300 + 1200) / 2 = 750.
    assertThat(metrics.medianTimeToFirstResponseSeconds()).isEqualTo(750L);
    assertThat(metrics.leadsConsideredForResponseTime()).isEqualTo(2);
  }

  @Test
  void excludesLeadsWithoutAnyResponse_fromMedianCalculation() {
    OffsetDateTime base = WINDOW_FROM.plusDays(1);

    // Caso: único lead sin respuesta -> mediana debe ser null, no NaN ni 0.
    Lead leadNoResponse = createLead("099333001", LeadStatus.PROVIDER_CONTACTED, base);
    appendEvent(leadNoResponse, "PROVIDER_CONTACTED", base);

    OpsDailyMetricsResponse onlyUnanswered = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);
    assertThat(onlyUnanswered.medianTimeToFirstResponseSeconds()).isNull();
    assertThat(onlyUnanswered.leadsConsideredForResponseTime()).isEqualTo(0);

    // Agregamos un segundo lead CON respuesta válida (100s) junto al que no
    // tiene respuesta: la mediana debe calcularse solo sobre el que sí tiene dato.
    Lead leadWithResponse = createLead("099333002", LeadStatus.ASSIGNED, base);
    appendEvent(leadWithResponse, "PROVIDER_CONTACTED", base);
    appendEvent(leadWithResponse, "PROVIDER_ACCEPTED", base.plusSeconds(100));

    OpsDailyMetricsResponse mixed = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);
    assertThat(mixed.medianTimeToFirstResponseSeconds()).isEqualTo(100L);
    assertThat(mixed.leadsConsideredForResponseTime()).isEqualTo(1);
  }

  @Test
  void computesExactRepeatRateAutodeclared_forRepeatVsUniqueCustomers() {
    OffsetDateTime base = WINDOW_FROM.plusDays(1);

    // Mismo cliente (teléfono normalizado igual, con y sin prefijo país) con 2
    // leads COMPLETED -> cuenta como "repeat".
    createLead("099444001", LeadStatus.COMPLETED, base);
    createLead("59899444001", LeadStatus.COMPLETED, base); // mismo número con prefijo 598

    // Cliente único con 1 lead COMPLETED -> no repite.
    createLead("099444002", LeadStatus.COMPLETED, base);

    // Cliente con lead NO completado -> no debe contar para el denominador.
    createLead("099444003", LeadStatus.IN_PROGRESS, base);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);

    // 2 clientes distintos con >=1 COMPLETED (099444001 y 099444002); de esos,
    // 1 repite (099444001, con 2 COMPLETED) -> repeat rate = 1/2 = 50%.
    assertThat(metrics.distinctClientsWithCompleted()).isEqualTo(2);
    assertThat(metrics.repeatClients()).isEqualTo(1);
    assertThat(metrics.repeatRateAutodeclaredPercentage()).isEqualTo(50.0);
  }

  /**
   * Hallazgo 2026-08-17 (lead #200): un cierre de tráfico [smoke] no debe
   * inflar ninguno de los agregados — ni el total, ni el fill rate, ni el
   * repeat rate.
   */
  @Test
  void excludesSmokeLeadsFromAllAggregates() {
    Lead real = createLead("099555001", LeadStatus.COMPLETED, WINDOW_FROM.plusDays(1));
    Lead smokeNew = createLead("099555002", LeadStatus.NEW, WINDOW_FROM.plusDays(1));
    smokeNew.setProblem("[smoke] prueba automatizada");
    leadRepository.saveAndFlush(smokeNew);
    Lead smokeCompleted = createLead("099555003", LeadStatus.COMPLETED, WINDOW_FROM.plusDays(1));
    smokeCompleted.setProblem("[smoke] otra prueba, cerrada como real");
    leadRepository.saveAndFlush(smokeCompleted);
    // Segundo COMPLETED del mismo smoke phone: si no se excluyera, "repetiría".
    Lead smokeCompletedRepeat = createLead("099555003", LeadStatus.COMPLETED, WINDOW_FROM.plusDays(1));
    smokeCompletedRepeat.setProblem("[smoke] tercera prueba");
    leadRepository.saveAndFlush(smokeCompletedRepeat);

    OpsDailyMetricsResponse metrics = opsMetricsService.dailyMetrics(WINDOW_FROM, WINDOW_TO);

    // Solo el lead real cuenta: total = 1, fill rate = 100% (1/1 COMPLETED).
    assertThat(metrics.totalLeadsCreated()).isEqualTo(1);
    assertThat(metrics.fillRatePercentage()).isEqualTo(100.0);
    // El smoke NEW no aparece en el desglose por status.
    assertThat(metrics.leadsByStatus().get("NEW")).isEqualTo(0L);
    assertThat(metrics.leadsByStatus().get("COMPLETED")).isEqualTo(1L);
    // Repeat rate: el teléfono smoke con 2 COMPLETED no cuenta como cliente
    // repetido — solo el real (099555001, 1 solo COMPLETED, no repite).
    assertThat(metrics.distinctClientsWithCompleted()).isEqualTo(1);
    assertThat(metrics.repeatClients()).isEqualTo(0);
  }

  @Test
  void stalledLeads48h_countsOpenLeadsOlderThan48hWithoutAcceptedProvider() {
    java.time.Clock frozenNow = java.time.Clock.fixed(
        WINDOW_TO.plusDays(30).toInstant(), ZoneOffset.UTC);
    OpsMetricsService serviceWithFrozenClock =
        new OpsMetricsService(leadRepository, leadEventRepository, frozenNow);
    java.time.OffsetDateTime now = java.time.OffsetDateTime.now(frozenNow);

    // Colgado real: NEW, creado hace 49h (>48h) -> cuenta.
    createLead("099666001", LeadStatus.NEW, now.minusHours(49));
    // PROVIDER_CONTACTED colgado hace 72h, sin aceptar -> cuenta.
    createLead("099666002", LeadStatus.PROVIDER_CONTACTED, now.minusHours(72));
    // Reciente (24h) -> no cuenta todavía.
    createLead("099666003", LeadStatus.NEW, now.minusHours(24));
    // ASSIGNED (ya aceptado) aunque sea viejo -> no cuenta.
    createLead("099666004", LeadStatus.ASSIGNED, now.minusHours(100));
    // COMPLETED (cerrado) aunque sea viejo -> no cuenta.
    createLead("099666005", LeadStatus.COMPLETED, now.minusHours(100));
    // CANCELLED (cerrado) aunque sea viejo -> no cuenta.
    createLead("099666006", LeadStatus.CANCELLED, now.minusHours(100));
    // Smoke, viejo y abierto -> no cuenta.
    Lead smokeStalled = createLead("099666007", LeadStatus.NEW, now.minusHours(200));
    smokeStalled.setProblem("[smoke] backlog de prueba");
    leadRepository.saveAndFlush(smokeStalled);

    OpsDailyMetricsResponse metrics = serviceWithFrozenClock.dailyMetrics(WINDOW_FROM, WINDOW_TO);

    assertThat(metrics.stalledLeads48h()).isEqualTo(2);
  }
}
