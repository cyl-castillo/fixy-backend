package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

/**
 * Panel admin (MVP): PATCH /api/ops/payments/{id}/mark-paid — "marcar
 * cobrada por transferencia" (caso real Nueva Era, $250 por fuera de MP).
 * Mismo basic auth de ops que el resto de /api/ops/**.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OpsPaymentsControllerTest {

  @Autowired
  private org.springframework.test.web.servlet.MockMvc mockMvc;

  @Autowired
  private LeadRepository leadRepository;

  @Autowired
  private ProviderRepository providerRepository;

  @Autowired
  private LeadPaymentRepository leadPaymentRepository;

  @Autowired
  private LeadEventRepository leadEventRepository;

  private LeadPayment createPendingLeadPayment() {
    Lead lead = new Lead();
    lead.setProblem("Problema de prueba mark-paid ops");
    lead.setPhone("099800222");
    lead.setChannel("whatsapp");
    lead.setStatus(LeadStatus.COMPLETED);
    lead = leadRepository.save(lead);

    Provider provider = new Provider();
    provider.setName("Proveedor Mark Paid Test");
    provider.setPhone("099800333");
    provider.setCategories("plomeria");
    provider = providerRepository.save(provider);

    LeadPayment payment = new LeadPayment();
    payment.setLeadId(lead.getId());
    payment.setProviderId(provider.getId());
    payment.setAmountCharged(new BigDecimal("2500.00"));
    payment.setCommissionRate(new BigDecimal("0.1000"));
    payment.setCommissionAmount(new BigDecimal("250.00"));
    payment.setCommissionStatus(CommissionStatus.PENDING);
    return leadPaymentRepository.save(payment);
  }

  @Test
  void shouldRejectUnauthenticatedRequests() throws Exception {
    LeadPayment payment = createPendingLeadPayment();

    mockMvc.perform(patch("/api/ops/payments/{id}/mark-paid", payment.getId()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldMarkPaidAndBeIdempotentOnRetry() throws Exception {
    LeadPayment payment = createPendingLeadPayment();

    mockMvc.perform(patch("/api/ops/payments/{id}/mark-paid", payment.getId())
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"Nueva Era transferencia\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commissionStatus").value("PAID"));

    LeadPayment afterFirst = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(afterFirst.getCommissionStatus()).isEqualTo(CommissionStatus.PAID);
    assertThat(afterFirst.getPaidAt()).isNotNull();
    assertThat(afterFirst.getMpPaymentId()).contains("Nueva Era transferencia");
    var firstPaidAt = afterFirst.getPaidAt();

    long commissionPaidEventsAfterFirst = leadEventRepository
        .findByLeadIdOrderByCreatedAtAsc(payment.getLeadId()).stream()
        .filter(event -> "COMMISSION_PAID".equals(event.getType()))
        .count();
    assertThat(commissionPaidEventsAfterFirst).isEqualTo(1);

    // Segundo click sobre la misma comisión: no rompe, no duplica el evento,
    // no pisa paidAt.
    mockMvc.perform(patch("/api/ops/payments/{id}/mark-paid", payment.getId())
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"note\":\"otro click\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commissionStatus").value("PAID"));

    LeadPayment afterSecond = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(afterSecond.getPaidAt()).isEqualTo(firstPaidAt);

    long commissionPaidEventsAfterSecond = leadEventRepository
        .findByLeadIdOrderByCreatedAtAsc(payment.getLeadId()).stream()
        .filter(event -> "COMMISSION_PAID".equals(event.getType()))
        .count();
    assertThat(commissionPaidEventsAfterSecond).isEqualTo(1);
  }

  @Test
  void shouldDefaultToTransferenciaNoteWhenBodyMissing() throws Exception {
    LeadPayment payment = createPendingLeadPayment();

    mockMvc.perform(patch("/api/ops/payments/{id}/mark-paid", payment.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commissionStatus").value("PAID"));

    LeadPayment reloaded = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getMpPaymentId()).isEqualTo("MANUAL:transferencia");
  }

  @Test
  void shouldReturn404ForUnknownPayment() throws Exception {
    mockMvc.perform(patch("/api/ops/payments/{id}/mark-paid", 999999L)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isNotFound());
  }

  @Test
  void shouldListPayments() throws Exception {
    mockMvc.perform(get("/api/ops/payments")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());
  }
}
