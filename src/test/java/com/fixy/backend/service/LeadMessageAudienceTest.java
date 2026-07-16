package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Modelo de audiencia por mensaje (provider_only/customer_only/all) y
 * senderName real del proveedor en vez del avatar "P" genérico. Hallazgos
 * del primer cobro real: (1) el aviso de comisión se veía en el chat del
 * cliente, (2) el proveedor no tenía nombre en el chat.
 */
@SpringBootTest
@Transactional
class LeadMessageAudienceTest {

  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadRepository leadRepository;
  @Autowired private ProviderRepository providerRepository;

  private Lead persistLead(String accessToken) {
    Lead lead = new Lead();
    lead.setProblem("Pedido de prueba audiencia");
    lead.setChannel("web-app");
    lead.setStatus(LeadStatus.NEW);
    lead.setAccessToken(accessToken);
    return leadRepository.save(lead);
  }

  private Provider persistProvider(String name) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("099000111");
    provider.setCategories("plomeria");
    return providerRepository.save(provider);
  }

  @Test
  void providerOnlyMessageIsInvisibleToCustomerButVisibleToProvider() {
    Lead lead = persistLead(UUID.randomUUID().toString());

    leadMessageService.postFromOps(lead.getId(), "fixy", "Tu comisión Fixy es de UYU 250.", "provider_only");

    List<LeadMessageResponse> customerView = leadMessageService.listForCustomer(lead.getId(), lead.getAccessToken());
    assertThat(customerView).isEmpty();

    List<LeadMessageResponse> providerView = leadMessageService.listForProvider(lead.getId(), lead.getAccessToken());
    assertThat(providerView).hasSize(1);
    assertThat(providerView.get(0).audience()).isEqualTo("provider_only");
  }

  @Test
  void customerOnlyMessageIsInvisibleToProviderButVisibleToCustomer() {
    Lead lead = persistLead(UUID.randomUUID().toString());

    leadMessageService.postFromOps(lead.getId(), "fixy", "Mensaje solo para el cliente", "customer_only");

    List<LeadMessageResponse> customerView = leadMessageService.listForCustomer(lead.getId(), lead.getAccessToken());
    assertThat(customerView).hasSize(1);

    List<LeadMessageResponse> providerView = leadMessageService.listForProvider(lead.getId(), lead.getAccessToken());
    assertThat(providerView).isEmpty();
  }

  @Test
  void defaultAudienceAllIsVisibleToBothCustomerAndProvider() {
    Lead lead = persistLead(UUID.randomUUID().toString());

    // Sin audiencia explícita: comportamiento histórico intacto.
    leadMessageService.postFromOps(lead.getId(), "fixy", "El proveedor marcó el trabajo como terminado.");

    List<LeadMessageResponse> customerView = leadMessageService.listForCustomer(lead.getId(), lead.getAccessToken());
    List<LeadMessageResponse> providerView = leadMessageService.listForProvider(lead.getId(), lead.getAccessToken());
    assertThat(customerView).hasSize(1);
    assertThat(providerView).hasSize(1);
    assertThat(customerView.get(0).audience()).isEqualTo("all");
  }

  @Test
  void senderNameResolvesToAssignedProviderRealName() {
    Lead lead = persistLead(UUID.randomUUID().toString());
    Provider provider = persistProvider("Nueva Era");
    lead.setAssignedProviderId(provider.getId());
    lead = leadRepository.save(lead);

    leadMessageService.postFromOps(lead.getId(), "provider", "Hola, ya estoy en camino");

    List<LeadMessageResponse> customerView = leadMessageService.listForCustomer(lead.getId(), lead.getAccessToken());
    assertThat(customerView).hasSize(1);
    assertThat(customerView.get(0).senderName()).isEqualTo("Nueva Era");
  }

  @Test
  void senderNameIsNullWhenNoAssignedProvider() {
    Lead lead = persistLead(UUID.randomUUID().toString());

    // No debería pasar en la práctica (postFromOps con sender=provider sin
    // asignación), pero cubrimos el fallback: nunca debe romper.
    leadMessageService.postFromOps(lead.getId(), "provider", "hola");

    List<LeadMessageResponse> customerView = leadMessageService.listForCustomer(lead.getId(), lead.getAccessToken());
    assertThat(customerView.get(0).senderName()).isNull();
  }

  @Test
  void senderNameIsNullForFixyAndCustomerSenders() {
    Lead lead = persistLead(UUID.randomUUID().toString());
    Provider provider = persistProvider("Nueva Era");
    lead.setAssignedProviderId(provider.getId());
    lead = leadRepository.save(lead);

    leadMessageService.postFromOps(lead.getId(), "fixy", "Aviso del sistema");
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), "hola necesito ayuda");

    List<LeadMessageResponse> customerView = leadMessageService.listForCustomer(lead.getId(), lead.getAccessToken());
    assertThat(customerView).allMatch(m -> m.senderName() == null);
  }
}
