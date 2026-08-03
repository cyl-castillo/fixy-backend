package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixy no puede decirle a un cliente "no llego a tu barrio" y al mismo tiempo
 * buscarle proveedor ahí.
 *
 * <p>Bug real (relevado en el embudo de prod el 2026-08-03): "Montes de
 * Solymar" se agregó el 2026-07-16 al catálogo de {@code AgentService} y
 * {@code LeadAgentService} pero no al de {@code LeadService}, que era una
 * cuarta lista escrita a mano. El pedido quedaba matcheable para el agente y
 * {@code zona_fuera_de_cobertura} para el cálculo de campos bloqueantes. Hay un
 * pedido real en esa zona en prod y Barométrica Nueva Era la declara en su
 * cobertura.
 *
 * <p>El test parametrizado es el que impide que esto vuelva a pasar: recorre
 * TODAS las zonas del catálogo, así que agregar una zona nueva a
 * {@link CoverageZone} sin sincronizar el resto rompe la suite.
 */
@SpringBootTest
@Transactional
class LeadZoneCoverageConsistencyTest {

  @Autowired private LeadService leadService;
  @Autowired private LeadRepository leadRepository;

  private LeadResponse persistLeadIn(String zone) {
    Lead lead = new Lead();
    lead.setProblem("[smoke] Pedido de plomería");
    lead.setChannel("web-app");
    lead.setStatus(LeadStatus.NEW);
    lead.setDetectedCategory("plomeria");
    lead.setLocation(zone);
    lead.setAccessToken("tok-cov-" + System.nanoTime());
    return leadService.get(leadRepository.save(lead).getId());
  }

  @ParameterizedTest
  @EnumSource(CoverageZone.class)
  void ningunaZonaDelCatalogoQuedaFueraDeCobertura(CoverageZone zone) {
    LeadResponse response = persistLeadIn(zone.label());

    assertThat(response.blockingFields())
        .as("zona %s del catálogo marcada fuera de cobertura", zone.label())
        .doesNotContain("zona_fuera_de_cobertura");
    assertThat(response.nextRecommendedAction()).isNotEqualTo("out_of_coverage_area");
  }

  /** El caso puntual que destapó el bug, escrito aparte para que se lea en el reporte. */
  @Test
  void montesDeSolymarNoEsFueraDeCobertura() {
    LeadResponse response = persistLeadIn("Montes de Solymar");

    assertThat(response.blockingFields()).doesNotContain("zona_fuera_de_cobertura");
    assertThat(response.readyForMatching()).isTrue();
  }

  /** Una zona que Fixy de verdad no cubre tiene que seguir marcándose. */
  @Test
  void unaZonaRealmenteFueraDeCoberturaSigueMarcada() {
    LeadResponse response = persistLeadIn("Pocitos");

    assertThat(response.blockingFields()).contains("zona_fuera_de_cobertura");
    assertThat(response.nextRecommendedAction()).isEqualTo("out_of_coverage_area");
  }
}
