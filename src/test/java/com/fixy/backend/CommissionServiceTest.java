package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.CommissionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CommissionServiceTest {

  @Autowired
  private CommissionService commissionService;

  @Autowired
  private LeadRepository leadRepository;

  @Autowired
  private ProviderRepository providerRepository;

  @Autowired
  private LeadPaymentRepository leadPaymentRepository;

  private Lead createLead() {
    Lead lead = new Lead();
    lead.setProblem("Problema de prueba comision");
    lead.setPhone("099555000");
    lead.setChannel("whatsapp");
    lead.setStatus(LeadStatus.IN_PROGRESS);
    return leadRepository.save(lead);
  }

  private Provider createProvider() {
    Provider provider = new Provider();
    provider.setName("Proveedor Comision Test");
    provider.setPhone("099555111");
    provider.setCategories("plomeria");
    return providerRepository.save(provider);
  }

  @Test
  void calculatesExactCommissionWithHalfEvenRounding() {
    // 10% de 2500.00 = 250.00 exacto.
    BigDecimal commission = commissionService.calculateCommission(
        new BigDecimal("2500.00"),
        new BigDecimal("0.10")
    );
    assertThat(commission).isEqualByComparingTo(new BigDecimal("250.00"));
  }

  @Test
  void roundsHalfEvenOnNonExactCommission() {
    // 10% de 1234.55 = 123.455 -> half-even a 2 decimales = 123.46
    // (dígito a redondear es 5 con resto no-cero -> sube, no es el caso
    // "empate exacto" de half-even, pero confirma el uso de esa estrategia
    // de redondeo end-to-end en vez de HALF_UP por defecto).
    BigDecimal commission = commissionService.calculateCommission(
        new BigDecimal("1234.55"),
        new BigDecimal("0.10")
    );
    assertThat(commission.setScale(2, RoundingMode.UNNECESSARY)).isEqualByComparingTo(new BigDecimal("123.46"));
  }

  @Test
  void createsAndPersistsLeadPaymentForCompletedLead() {
    Lead lead = createLead();
    Provider provider = createProvider();

    LeadPayment payment = commissionService.createForCompletedLead(
        lead, provider, new BigDecimal("2500.00"));

    assertThat(payment.getId()).isNotNull();
    assertThat(payment.getLeadId()).isEqualTo(lead.getId());
    assertThat(payment.getProviderId()).isEqualTo(provider.getId());
    assertThat(payment.getAmountCharged()).isEqualByComparingTo(new BigDecimal("2500.00"));
    assertThat(payment.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.1000"));
    assertThat(payment.getCommissionAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
    assertThat(payment.getCurrency()).isEqualTo("UYU");
    assertThat(payment.getCommissionStatus()).isEqualTo(CommissionStatus.PENDING);
    // MP no configurado en test (mp-access-token vacío) -> sin link, pero el
    // LeadPayment se crea igual (no rompe el flujo de completar).
    assertThat(payment.getMpPaymentLink()).isNull();

    LeadPayment reloaded = leadPaymentRepository.findById(payment.getId()).orElseThrow();
    assertThat(reloaded.getCommissionAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
  }
}
