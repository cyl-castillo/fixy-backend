package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.AppUser;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.UserLead;
import com.fixy.backend.repository.AppUserRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.UserLeadRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Salto 1 de memoria de cliente (PLAN_SUPERAPP_CLIENTE.md / ARQUITECTURA_SUPERAPP.md).
 * Verifica LeadAgentService.buildContext directamente (package-private) para no
 * depender de un LLM real: mismo enfoque que LeadAgentFallbackTest, que ya
 * verifica comportamiento conversacional sin llamar a un proveedor externo.
 */
@SpringBootTest
class LeadAgentCustomerMemoryTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadRepository leadRepository;
  @Autowired private AppUserRepository appUserRepository;
  @Autowired private UserLeadRepository userLeadRepository;

  @Test
  void loggedInCustomerWithPreviousLeadsGetsMemorySummaryInContext() {
    AppUser user = new AppUser();
    user.setGoogleSub("test-sub-" + System.nanoTime());
    user.setEmail("cliente@example.com");
    user = appUserRepository.save(user);

    Lead previous1 = persistLead("plomeria", "Shangrilá", LeadStatus.COMPLETED, "Fredy Carbonero");
    Lead previous2 = persistLead("jardineria", "Solymar", LeadStatus.CANCELLED, null);
    Lead current = persistLead("aires_acondicionados", "Lagomar", LeadStatus.NEW, null);

    linkUserToLead(user.getId(), previous1.getId());
    linkUserToLead(user.getId(), previous2.getId());
    linkUserToLead(user.getId(), current.getId());

    String context = leadAgentService.buildContext(current);

    assertThat(context).contains("Historial con este cliente");
    assertThat(context).contains("plomería");
    assertThat(context).contains("Shangrilá");
    assertThat(context).contains("Fredy Carbonero");
    assertThat(context).contains("jardinería");
    assertThat(context).contains("Solymar");
    // El lead actual no debe listarse a sí mismo como "historial previo".
    assertThat(context).doesNotContain("aire acondicionado en Lagomar, estado recién creado");
  }

  @Test
  void anonymousCustomerGetsNoMemorySection() {
    Lead anonymousLead = persistLead("plomeria", "Solymar", LeadStatus.NEW, null);

    String context = leadAgentService.buildContext(anonymousLead);

    assertThat(context).doesNotContain("Historial con este cliente");
  }

  @Test
  void loggedInCustomerWithoutPreviousLeadsGetsNoMemorySection() {
    AppUser user = new AppUser();
    user.setGoogleSub("test-sub-no-history-" + System.nanoTime());
    user.setEmail("nuevo@example.com");
    user = appUserRepository.save(user);

    Lead onlyLead = persistLead("plomeria", "Solymar", LeadStatus.NEW, null);
    linkUserToLead(user.getId(), onlyLead.getId());

    String context = leadAgentService.buildContext(onlyLead);

    // El único lead vinculado ES el actual — no hay "previos" que resumir.
    assertThat(context).doesNotContain("Historial con este cliente");
  }

  private Lead persistLead(String category, String location, LeadStatus status, String assignedProvider) {
    Lead lead = new Lead();
    lead.setProblem("Pedido de prueba");
    lead.setChannel("web-app");
    lead.setDetectedCategory(category);
    lead.setLocation(location);
    lead.setUrgency("media");
    lead.setStatus(status);
    lead.setReadyForMatching(true);
    lead.setAssignedProvider(assignedProvider);
    return leadRepository.save(lead);
  }

  private void linkUserToLead(Long userId, Long leadId) {
    UserLead link = new UserLead();
    link.setUserId(userId);
    link.setLeadId(leadId);
    link.setCreatedAt(OffsetDateTime.now());
    userLeadRepository.save(link);
    // Pequeño delay lógico no necesario: createdAt distinto alcanza para el orden
    // porque cada persistLead+link ocurre en llamadas secuenciales separadas.
  }
}
