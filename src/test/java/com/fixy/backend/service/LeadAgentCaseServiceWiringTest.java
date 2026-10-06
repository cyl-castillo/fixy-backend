package com.fixy.backend.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.core.agent.LlmGateway;
import com.fixy.backend.core.agent.TurnPolicy;
import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.UserLeadRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 3 (CORE_FASE3_CONTRATO.md, pieza 5 y decisión 5): el constructor legacy de 13 argumentos
 * de {@link LeadAgentService} arma un {@link LeadCaseService} desde los MISMOS mocks de
 * {@code LeadRepository}/{@code LeadTimelineService} que recibe, así que lo que el loop le pide al
 * caso sigue pudiéndose verificar sobre esos mocks.
 */
class LeadAgentCaseServiceWiringTest {

  private final LeadRepository leadRepository = mock(LeadRepository.class);
  private final LeadTimelineService timeline = mock(LeadTimelineService.class);
  private final LeadMessageService leadMessageService = mock(LeadMessageService.class);
  private final TelegramNotifyService telegram = mock(TelegramNotifyService.class);
  private final TurnPolicy turnPolicy = mock(TurnPolicy.class);

  private final LeadAgentService service = new LeadAgentService(new ObjectMapper(), leadMessageService,
      leadRepository, mock(UserLeadRepository.class), mock(ProviderCatalogService.class),
      mock(WhatsAppMenuService.class), timeline, mock(AgentService.class), telegram,
      mock(SearchDeadlineService.class), mock(LlmGateway.class), turnPolicy, mock(LeadMatchingService.class));

  private Lead lead() {
    Lead lead = new Lead();
    lead.setId(5L);
    lead.setProblem("pedido de prueba");
    when(leadRepository.findById(5L)).thenReturn(Optional.of(lead));
    when(leadMessageService.recentForAgent(any(), anyInt())).thenReturn(List.of());
    return lead;
  }

  @Test
  void elEventoDeAyudaHumanaVaAlTimelineMockeadoPorElCaso() {
    Lead lead = lead();

    service.requestHumanHelp(5L);

    verify(timeline).appendEvent(lead, "HUMAN_HELP_REQUESTED", "customer", "El cliente pidió hablar con una persona");
  }

  @Test
  void laIdempotenciaDelEscalamientoConsultaElTimelineMockeadoPorElCaso() {
    lead();
    when(timeline.hasEvent(5L, "CUSTOMER_NOTIFIED_ESCALATION")).thenReturn(true);

    service.dispatchEscalation(5L, new LeadAgentService.AgentAction("escalate", "motivo", "resumen"));

    verify(timeline).hasEvent(5L, "CUSTOMER_NOTIFIED_ESCALATION");
    verify(leadMessageService, never()).postFromAgent(any(), anyString());
    verify(timeline, never()).appendEvent(any(), anyString(), anyString(), anyString());
  }

  @Test
  void elEscalamientoNuevoPostea_registraElEventoYAvisaPorElCaso() {
    Lead lead = lead();
    when(timeline.hasEvent(5L, "CUSTOMER_NOTIFIED_ESCALATION")).thenReturn(false);

    service.dispatchEscalation(5L, new LeadAgentService.AgentAction("escalate", "motivo", "resumen"));

    // turnPolicy es un mock: safe() devuelve null, así que el texto del evento no es lo que se verifica.
    verify(timeline).appendEvent(eq(lead), eq("CUSTOMER_NOTIFIED_ESCALATION"), eq("system"), any());
  }
}
