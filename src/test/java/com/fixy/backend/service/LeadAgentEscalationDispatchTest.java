package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.repository.LeadRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Salto 2 del cerebro agéntico (dispatcher de acciones, ver
 * ARQUITECTURA_SUPERAPP.md): cuando el turno conversacional resuelve
 * action.type="escalate", LeadAgentService.dispatchEscalation debe:
 *  (a) postear un mensaje honesto al cliente en el chat del lead,
 *  (b) avisar a Carlos vía TelegramNotifyService (mockeado acá — el
 *      contenido exacto del aviso ya está cubierto por TelegramNotifyServiceTest),
 *  (c) ser idempotente: un segundo dispatch del mismo lead no repite ni el
 *      mensaje al cliente ni el aviso a Telegram,
 *  (d) no escalar leads de prueba ("[smoke]" en el problema), mismo criterio
 *      que ya usan los avisos de oportunidad.
 *
 * dispatchEscalation es package-private (mismo patrón que buildContext en
 * LeadAgentCustomerMemoryTest) para poder testearlo sin depender de un LLM real.
 */
@SpringBootTest
class LeadAgentEscalationDispatchTest {

  @Autowired
  private LeadAgentService leadAgentService;

  @Autowired
  private LeadRepository leadRepository;

  @Autowired
  private LeadMessageService leadMessageService;

  @MockitoBean
  private TelegramNotifyService telegramNotifyService;

  private Lead persistLead(String problem) {
    Lead lead = new Lead();
    lead.setName("Cliente Test");
    lead.setPhone("099111222");
    lead.setProblem(problem);
    lead.setChannel("chat");
    lead.setDetectedCategory("plomeria");
    lead.setLocation("Solymar");
    lead.setUrgency("media");
    lead.setMissingFields("");
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.NEW);
    lead.setNotes("");
    lead.setHistory("test");
    lead.setAccessToken("tok-escalation-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  private LeadAgentService.AgentAction escalateAction() {
    return new LeadAgentService.AgentAction("escalate", "cliente frustrado", "el proveedor no vino");
  }

  @Test
  void escalate_postsHonestMessageToCustomerAndNotifiesTelegram() {
    Lead lead = persistLead("Se me rompió la canilla y el proveedor no vino");

    leadAgentService.dispatchEscalation(lead.getId(), escalateAction());

    List<LeadMessage> messages = leadMessageService.recentForAgent(lead.getId(), 10);
    assertThat(messages).anySatisfy(m -> {
      assertThat(m.getSender()).isEqualTo("fixy");
      assertThat(m.getText()).contains("Te paso con una persona de Fixy");
    });

    verify(telegramNotifyService, times(1))
        .notifyEscalation(any(Lead.class), eq("cliente frustrado"), eq("el proveedor no vino"));
  }

  @Test
  void escalate_isIdempotent_secondDispatchDoesNotRepeat() {
    Lead lead = persistLead("Reclamo: el proveedor no vino");

    leadAgentService.dispatchEscalation(lead.getId(), escalateAction());
    long firstCount = leadMessageService.recentForAgent(lead.getId(), 10).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .count();

    leadAgentService.dispatchEscalation(lead.getId(), escalateAction());
    long secondCount = leadMessageService.recentForAgent(lead.getId(), 10).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .count();

    assertThat(secondCount).isEqualTo(firstCount);
    verify(telegramNotifyService, times(1))
        .notifyEscalation(any(Lead.class), anyString(), anyString());
  }

  @Test
  void smokeLead_doesNotEscalate() {
    Lead lead = persistLead("[smoke] prueba automatizada de humo");

    leadAgentService.dispatchEscalation(lead.getId(), escalateAction());

    List<LeadMessage> messages = leadMessageService.recentForAgent(lead.getId(), 10);
    assertThat(messages).noneMatch(m -> "fixy".equals(m.getSender())
        && m.getText() != null && m.getText().contains("Te paso con una persona"));
    verify(telegramNotifyService, never())
        .notifyEscalation(any(Lead.class), anyString(), anyString());
  }
}
