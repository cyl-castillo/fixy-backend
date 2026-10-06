package com.fixy.backend.service;

import com.fixy.backend.core.cases.CaseService;
import com.fixy.backend.core.domain.Playbook;
import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.LeadRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Core Fase 3 (ver CORE_FASE3_CONTRATO.md): {@link CaseService} sobre {@link Lead}. Delega en
 * {@link LeadRepository} y {@link LeadTimelineService} (capas strangler, por eso vive en
 * {@code service} y no en {@code core.cases}); la única lógica propia es validar los atributos
 * contra el {@link Playbook}.
 *
 * <p>No agrega transacción: igual que antes, cada {@code save} commitea solo (el loop del turno
 * no es transaccional).
 */
@Service
public class LeadCaseService implements CaseService {

  private final LeadRepository leadRepository;
  private final LeadTimelineService leadTimelineService;

  /**
   * Constructor público para los constructores legacy de {@code LeadAgentService} y
   * {@code LeadMatchingService}, que lo arman desde los mismos mocks que ya reciben los tests.
   */
  @Autowired
  public LeadCaseService(LeadRepository leadRepository, LeadTimelineService leadTimelineService) {
    this.leadRepository = leadRepository;
    this.leadTimelineService = leadTimelineService;
  }

  @Override
  public Lead get(Long id) {
    return leadRepository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("Lead inexistente: " + id));
  }

  @Override
  public Lead save(Lead lead) {
    return leadRepository.save(lead);
  }

  @Override
  public void appendEvent(Lead lead, String type, String actor, String message) {
    leadTimelineService.appendEvent(lead, type, actor, message);
  }

  @Override
  public boolean hasEvent(Lead lead, String type) {
    return leadTimelineService.hasEvent(lead.getId(), type);
  }

  @Override
  public void setAttribute(Lead lead, String name, Object value) {
    Playbook.get().validateAttribute(name, value);
    // Mapa nuevo (no mutar el actual): así Hibernate ve el cambio sin depender de cómo compara
    // el valor convertido en el dirty-check.
    Map<String, Object> updated = new LinkedHashMap<>(lead.getAttributes());
    updated.put(name, value);
    lead.setAttributes(updated);
    leadRepository.save(lead);
  }

  @Override
  public Object attribute(Lead lead, String name) {
    return lead.getAttributes().get(name);
  }
}
