package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Comisión vencida pausa el matching" (FIXY_COBRANZAS.md): a los 7 días
 * una comisión PENDING pasa a OVERDUE, con las tres excepciones
 * innegociables del playbook (primera comisión, disputa sin resolver,
 * [smoke]).
 *
 * Mismo patrón que ReengagementSchedulerTest: createdAt lo fija
 * @PrePersist, así que se envejece por SQL nativo directo sobre la fila ya
 * persistida.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.collections.enabled=true",
    "fixy.collections.overdue-days=7"
})
@Transactional
class CommissionOverdueSchedulerTest {

  @Autowired private CommissionOverdueScheduler scheduler;
  @Autowired private LeadRepository leadRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadPaymentRepository leadPaymentRepository;
  @Autowired private LeadTimelineService timelineService;
  @Autowired private ProviderCatalogService catalogService;
  @Autowired private ProviderOpportunityService opportunityService;
  @Autowired private jakarta.persistence.EntityManager entityManager;

  private Lead persistLead(String problem) {
    Lead lead = new Lead();
    lead.setProblem(problem);
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.COMPLETED);
    lead.setAccessToken("tok-overdue-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  private Provider persistProvider(String name) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("0990000" + System.nanoTime() % 1000);
    provider.setCategories("plomeria");
    provider.setAccessToken("ptok-" + System.nanoTime());
    return providerRepository.save(provider);
  }

  private LeadPayment persistPayment(Lead lead, Provider provider, CommissionStatus status) {
    LeadPayment payment = new LeadPayment();
    payment.setLeadId(lead.getId());
    payment.setProviderId(provider.getId());
    payment.setAmountCharged(new BigDecimal("2500.00"));
    payment.setCommissionRate(new BigDecimal("0.1000"));
    payment.setCommissionAmount(new BigDecimal("250.00"));
    payment.setCommissionStatus(status);
    return leadPaymentRepository.save(payment);
  }

  private void ageLeadPayment(LeadPayment payment, long daysAgo) {
    entityManager.createNativeQuery("UPDATE lead_payments SET created_at = :ts WHERE id = :id")
        .setParameter("ts", OffsetDateTime.now().minusDays(daysAgo))
        .setParameter("id", payment.getId())
        .executeUpdate();
    entityManager.clear();
  }

  @Test
  void venceALosSieteDiasExactos() {
    Lead lead = persistLead("arreglo de caño");
    Provider provider = persistProvider("Plomero Siete Dias");
    // Segunda comisión del proveedor (no es la primera) para no activar la
    // tolerancia del doble plazo.
    persistPayment(lead, provider, CommissionStatus.PAID);
    LeadPayment payment = persistPayment(lead, provider, CommissionStatus.PENDING);
    ageLeadPayment(payment, 7);

    int overdue = scheduler.processOnce();

    assertThat(overdue).isEqualTo(1);
    LeadPayment reloaded = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getCommissionStatus()).isEqualTo(CommissionStatus.OVERDUE);
  }

  @Test
  void noVenceALosSeisDias() {
    Lead lead = persistLead("arreglo de caño");
    Provider provider = persistProvider("Plomero Seis Dias");
    persistPayment(lead, provider, CommissionStatus.PAID);
    LeadPayment payment = persistPayment(lead, provider, CommissionStatus.PENDING);
    ageLeadPayment(payment, 6);

    int overdue = scheduler.processOnce();

    assertThat(overdue).isZero();
    LeadPayment reloaded = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getCommissionStatus()).isEqualTo(CommissionStatus.PENDING);
  }

  @Test
  void primeraComisionDelProveedorToleraElDoblePlazo() {
    Lead lead = persistLead("arreglo de caño");
    Provider provider = persistProvider("Plomero Nuevo");
    // Único LeadPayment del proveedor: es su primera comisión.
    LeadPayment payment = persistPayment(lead, provider, CommissionStatus.PENDING);
    ageLeadPayment(payment, 7);

    assertThat(scheduler.processOnce()).isZero();
    assertThat(leadPaymentRepository.findById(payment.getId()).orElseThrow().getCommissionStatus())
        .isEqualTo(CommissionStatus.PENDING);

    ageLeadPayment(payment, 14);

    assertThat(scheduler.processOnce()).isEqualTo(1);
    assertThat(leadPaymentRepository.findById(payment.getId()).orElseThrow().getCommissionStatus())
        .isEqualTo(CommissionStatus.OVERDUE);
  }

  @Test
  void leadDisputadoSinResolverFrenaLaEscalera() {
    Lead lead = persistLead("arreglo de caño");
    lead.setDisputed(true);
    leadRepository.save(lead);
    Provider provider = persistProvider("Plomero Disputado");
    persistPayment(lead, provider, CommissionStatus.PAID);
    LeadPayment payment = persistPayment(lead, provider, CommissionStatus.PENDING);
    ageLeadPayment(payment, 30);

    assertThat(scheduler.processOnce()).isZero();
    assertThat(leadPaymentRepository.findById(payment.getId()).orElseThrow().getCommissionStatus())
        .isEqualTo(CommissionStatus.PENDING);
  }

  @Test
  void leadConDisputaResueltaSiVence() {
    Lead lead = persistLead("arreglo de caño");
    lead.setDisputed(true);
    lead.setDisputeResolvedAt(OffsetDateTime.now());
    leadRepository.save(lead);
    Provider provider = persistProvider("Plomero Disputa Resuelta");
    persistPayment(lead, provider, CommissionStatus.PAID);
    LeadPayment payment = persistPayment(lead, provider, CommissionStatus.PENDING);
    ageLeadPayment(payment, 7);

    assertThat(scheduler.processOnce()).isEqualTo(1);
  }

  @Test
  void leadSmokeNuncaVence() {
    Lead lead = persistLead("[smoke] prueba de humo");
    Provider provider = persistProvider("Plomero Smoke");
    persistPayment(lead, provider, CommissionStatus.PAID);
    LeadPayment payment = persistPayment(lead, provider, CommissionStatus.PENDING);
    ageLeadPayment(payment, 365);

    assertThat(scheduler.processOnce()).isZero();
  }

  @Test
  void alVencerEmiteEventoDeTimelineUnaSolaVez() {
    Lead lead = persistLead("arreglo de caño");
    Provider provider = persistProvider("Plomero Timeline");
    persistPayment(lead, provider, CommissionStatus.PAID);
    LeadPayment payment = persistPayment(lead, provider, CommissionStatus.PENDING);
    ageLeadPayment(payment, 7);

    scheduler.processOnce();
    // Segundo ciclo: la comisión ya es OVERDUE, no PENDING -> no se
    // reprocesa (el filtro por status PENDING es el guard natural).
    int secondRun = scheduler.processOnce();

    assertThat(secondRun).isZero();
    long overdueEvents = timelineService.listForLead(lead.getId()).stream()
        .filter(event -> "COMMISSION_OVERDUE".equals(event.type()))
        .count();
    assertThat(overdueEvents).isEqualTo(1);
  }

  @Test
  void proveedorConComisionOverdueQuedaExcluidoDeFindMatchesYBandejaPeroAsignadosIntactos() {
    Lead completedLead = persistLead("arreglo de caño ya hecho");
    Provider providerDraft = persistProvider("Plomero Pausado");
    providerDraft.setPrimaryZone("Pocitos");
    providerDraft.setStatus(ProviderStatus.AVAILABLE);
    final Provider provider = providerRepository.save(providerDraft);
    persistPayment(completedLead, provider, CommissionStatus.PAID);
    LeadPayment payment = persistPayment(completedLead, provider, CommissionStatus.PENDING);
    ageLeadPayment(payment, 7);

    // Trabajo YA asignado a este proveedor, antes de que venza la comisión.
    Lead assignedLead = persistLead("otro arreglo en curso");
    assignedLead.setStatus(LeadStatus.ASSIGNED);
    assignedLead.setAssignedProviderId(provider.getId());
    leadRepository.save(assignedLead);

    int overdue = scheduler.processOnce();
    assertThat(overdue).isEqualTo(1);

    // Matching de leads nuevos: el proveedor pausado no aparece.
    assertThat(catalogService.findMatches("plomeria", "Pocitos"))
        .noneMatch(item -> item.id().equals(provider.getId()));

    // Bandeja de oportunidades nuevas: vacía para él.
    Provider reloadedProvider = providerRepository.findById(provider.getId()).orElseThrow();
    assertThat(opportunityService.listFor(reloadedProvider)).isEmpty();

    // Trabajo ya asignado: intacto, sigue apareciendo en sus asignados.
    Lead reloadedAssigned = leadRepository.findById(assignedLead.getId()).orElseThrow();
    assertThat(reloadedAssigned.getAssignedProviderId()).isEqualTo(provider.getId());
    assertThat(reloadedAssigned.getStatus()).isEqualTo(LeadStatus.ASSIGNED);
  }
}
