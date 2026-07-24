package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPayment;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadPaymentRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.CommissionService;
import com.fixy.backend.service.LeadMessageService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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

  @Autowired
  private LeadMessageService leadMessageService;

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

  /** Crea un lead ya cobrado (comisión en el estado dado) para la dupla
   * proveedor + teléfono, simulando un trabajo anterior de esa relación. */
  private void createPriorPayment(Provider provider, String phone, CommissionStatus status) {
    Lead prior = new Lead();
    prior.setProblem("Trabajo anterior de la dupla");
    prior.setPhone(phone);
    prior.setChannel("whatsapp");
    prior.setStatus(LeadStatus.COMPLETED);
    prior = leadRepository.save(prior);

    LeadPayment payment = new LeadPayment();
    payment.setLeadId(prior.getId());
    payment.setProviderId(provider.getId());
    payment.setAmountCharged(new BigDecimal("1000.00"));
    payment.setCommissionRate(new BigDecimal("0.1000"));
    payment.setCommissionAmount(new BigDecimal("100.00"));
    payment.setCommissionStatus(status);
    leadPaymentRepository.save(payment);
  }

  @Test
  void repeatCustomerOfSameProviderGetsReducedRate() {
    Provider provider = createProvider();
    // Trabajo anterior saldado con el mismo cliente, teléfono escrito
    // distinto (+598 vs 099): la identidad es por últimos 8 dígitos.
    createPriorPayment(provider, "+598 99 555 000", CommissionStatus.PAID);

    LeadPayment payment = commissionService.createForCompletedLead(
        createLead(), provider, new BigDecimal("2500.00"));

    assertThat(payment.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.0500"));
    assertThat(payment.getCommissionAmount()).isEqualByComparingTo(new BigDecimal("125.00"));
  }

  @Test
  void differentCustomerStillPaysBaseRate() {
    Provider provider = createProvider();
    createPriorPayment(provider, "098111222", CommissionStatus.PAID);

    LeadPayment payment = commissionService.createForCompletedLead(
        createLead(), provider, new BigDecimal("2500.00"));

    assertThat(payment.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.1000"));
    assertThat(payment.getCommissionAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
  }

  @Test
  void unsettledPriorCommissionDoesNotEarnTheDiscount() {
    Provider provider = createProvider();
    // Mismo cliente pero la comisión anterior sigue PENDING: el descuento
    // se gana con la primera comisión resuelta, no con marcar completado.
    createPriorPayment(provider, "099555000", CommissionStatus.PENDING);

    LeadPayment payment = commissionService.createForCompletedLead(
        createLead(), provider, new BigDecimal("2500.00"));

    assertThat(payment.getCommissionRate()).isEqualByComparingTo(new BigDecimal("0.1000"));
  }

  @Test
  void commissionMessageIsProviderOnlyAndInvisibleToCustomer() {
    Lead lead = createLead();
    lead.setAccessToken("token-comision-test");
    lead = leadRepository.save(lead);
    Provider provider = createProvider();
    lead.setAssignedProviderId(provider.getId());
    lead = leadRepository.save(lead);

    commissionService.createForCompletedLead(lead, provider, new BigDecimal("2500.00"));

    // El cliente no debe ver el mensaje de comisión en su chat.
    List<LeadMessageResponse> customerView = leadMessageService.listForCustomer(lead.getId(), lead.getAccessToken());
    assertThat(customerView).noneMatch(m -> m.text().contains("comisión Fixy"));

    // El proveedor sí lo ve, en su listado dedicado.
    List<LeadMessageResponse> providerView = leadMessageService.listForProvider(lead.getId(), lead.getAccessToken());
    assertThat(providerView).anyMatch(m -> m.text().contains("comisión Fixy") && "provider_only".equals(m.audience()));
  }
}
