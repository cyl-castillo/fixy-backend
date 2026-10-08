package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bug del 2026-10-08 (primer SÍ real por WhatsApp, lead #275): el matching deja el
 * lead PROVIDER_CONTACTED con {@code assignedProviderId} = el técnico contactado, y
 * {@code assignIfUnclaimed} exigía {@code assignedProviderId is null}, así que el SÍ
 * del técnico al que se le ofreció el trabajo volvía con 409 "otro proveedor tomó
 * este trabajo primero". El panel no lo sufría porque acepta por otro endpoint.
 */
@SpringBootTest
class LeadAssignmentContactedProviderTest {

  @Autowired LeadRepository leadRepository;
  @Autowired ProviderRepository providerRepository;
  @Autowired LeadAssignmentService leadAssignmentService;

  @Test
  void elTecnicoContactadoPuedeAceptar() {
    Provider carlos = provider("Tecnico Contactado Acepta");
    Lead lead = contactedLead(carlos);

    Lead assigned = leadAssignmentService.acceptForProvider(
        lead.getId(), carlos, carlos.getName() + " aceptó por WhatsApp: SÍ");

    assertThat(assigned.getStatus()).isEqualTo(LeadStatus.ASSIGNED);
    assertThat(assigned.getAssignedProviderId()).isEqualTo(carlos.getId());
    assertThat(assigned.getAssignedProvider()).isEqualTo(carlos.getName());
    Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.ASSIGNED);
  }

  @Test
  void otroTecnicoNoPuedeRobarUnLeadReservado() {
    Provider contactado = provider("Tecnico Reservado");
    Provider intruso = provider("Tecnico Intruso");
    Lead lead = contactedLead(contactado);

    assertThatThrownBy(() -> leadAssignmentService.acceptForProvider(
        lead.getId(), intruso, "intruso aceptó por WhatsApp: SÍ"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));

    Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(reloaded.getAssignedProviderId()).isEqualTo(contactado.getId());
  }

  @Test
  void unLeadSinReservaSigueSiendoAceptable() {
    Provider libre = provider("Tecnico Sin Reserva");
    Lead lead = new Lead();
    lead.setProblem("[smoke] plomería sin reserva");
    lead.setDetectedCategory("plomeria");
    lead.setLocation("Lagomar");
    lead.setStatus(LeadStatus.NEW);
    lead = leadRepository.save(lead);

    Lead assigned = leadAssignmentService.acceptForProvider(lead.getId(), libre, "tomó desde la bandeja");

    assertThat(assigned.getStatus()).isEqualTo(LeadStatus.ASSIGNED);
    assertThat(assigned.getAssignedProviderId()).isEqualTo(libre.getId());
  }

  @Test
  void unLeadYaAsignadoNoSeVuelveAAsignar() {
    Provider duenio = provider("Tecnico Duenio");
    Lead lead = contactedLead(duenio);
    leadAssignmentService.acceptForProvider(lead.getId(), duenio, "SÍ");

    assertThatThrownBy(() -> leadAssignmentService.acceptForProvider(lead.getId(), duenio, "SÍ otra vez"))
        .isInstanceOf(ResponseStatusException.class);
  }

  private Lead contactedLead(Provider provider) {
    Lead lead = new Lead();
    lead.setProblem("[smoke] plomería reservada para " + provider.getName());
    lead.setDetectedCategory("plomeria");
    lead.setLocation("Lagomar");
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    return leadRepository.save(lead);
  }

  private Provider provider(String name) {
    Provider p = new Provider();
    p.setName(name);
    p.setPhone("0998" + Math.abs(name.hashCode() % 1000000));
    p.setCategories("plomeria");
    p.setPrimaryZone("Lagomar");
    p.setStatus(ProviderStatus.AVAILABLE);
    p.setAccessToken("token-" + name.replace(' ', '-'));
    return providerRepository.save(p);
  }
}
