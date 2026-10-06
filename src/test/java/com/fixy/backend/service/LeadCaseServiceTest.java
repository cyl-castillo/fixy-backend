package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.LeadRepository;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Core Fase 3 (CORE_FASE3_CONTRATO.md, pieza 2): LeadCaseService delega sin lógica propia. */
class LeadCaseServiceTest {

  private final LeadRepository leadRepository = mock(LeadRepository.class);
  private final LeadTimelineService timeline = mock(LeadTimelineService.class);
  private final LeadCaseService service = new LeadCaseService(leadRepository, timeline);

  private static Lead lead(long id) {
    Lead lead = new Lead();
    lead.setId(id);
    return lead;
  }

  @Test
  void getDevuelveElLeadYLanzaSiNoExiste() {
    Lead existing = lead(7);
    when(leadRepository.findById(7L)).thenReturn(Optional.of(existing));
    when(leadRepository.findById(8L)).thenReturn(Optional.empty());

    assertThat(service.get(7L)).isSameAs(existing);
    assertThatThrownBy(() -> service.get(8L)).isInstanceOf(NoSuchElementException.class).hasMessageContaining("8");
  }

  @Test
  void saveYAppendEventYHasEventDelegan() {
    Lead lead = lead(5);
    when(leadRepository.save(lead)).thenReturn(lead);
    when(timeline.hasEvent(5L, "X")).thenReturn(true);

    assertThat(service.save(lead)).isSameAs(lead);
    service.appendEvent(lead, "T", "system", "msg");
    assertThat(service.hasEvent(lead, "X")).isTrue();
    assertThat(service.hasEvent(lead, "Y")).isFalse();

    verify(leadRepository).save(lead);
    verify(timeline).appendEvent(lead, "T", "system", "msg");
  }

  @Test
  void setAttributeValidaContraElPlaybookYNoPersisteSiRechaza() {
    Lead lead = lead(5);
    // El playbook de Fixy no declara atributos: cualquier clave es desconocida.
    assertThatThrownBy(() -> service.setAttribute(lead, "rooms", 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rooms");
    verify(leadRepository, never()).save(any());
    assertThat(lead.getAttributes()).isEmpty();
  }

  @Test
  void attributeLeeDelMapaDelCaso() {
    Lead lead = lead(5);
    lead.getAttributes().put("rooms", 3);
    assertThat(service.attribute(lead, "rooms")).isEqualTo(3);
    assertThat(service.attribute(lead, "otra")).isNull();
  }
}
