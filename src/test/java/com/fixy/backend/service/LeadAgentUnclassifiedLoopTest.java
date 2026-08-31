package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * El bucle del pedido que el clasificador no entiende (bug reportado dos veces
 * por el canal de desarrollo, 2026-08-28 22:34 y 2026-08-29 14:25: "queda en
 * bucle y no envía los mensajes"). En prod son los leads #261, #262 y #263:
 * tres vecinos pidieron un carpintero, limpiar un parrillero y un flete para
 * una mudanza; el LLM devolvió "sin respuesta utilizable" en los seis turnos y
 * el fallback determinista les contestó DOS VECES la misma pregunta genérica.
 * Los tres se fueron y los tres quedaron con problem "(pendiente)" — pedidos
 * reales invisibles en el board.
 *
 * El guard anti-repetición del 2026-08-27 (LeadAgentFallbackRepeatTest) no los
 * cubría: solo entra con el lead ya clasificado (readyForMatching o proveedor
 * en línea), y un pedido incomprendido no tiene ni categoría ni zona.
 *
 * Montaje idéntico al de LeadAgentFallbackRepeatTest: agente encendido y LLM
 * sin credenciales, que es exactamente lo que pasó en prod. Sin @Transactional
 * porque respondToCustomerAsync corre @Async en otro hilo. Reengagement
 * apagado: en prod fue el que posteó el "¿Seguís por ahí?" 13 minutos después,
 * y acá solo agregaría ruido al conteo de mensajes.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token=",
    "fixy.reengagement.enabled=false"
})
class LeadAgentUnclassifiedLoopTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository messageRepository;
  @Autowired private LeadRepository leadRepository;

  @MockitoBean private TelegramNotifyService telegramNotifyService;

  /** Un lead recién nacido del chat, tal cual lo crea LeadService: sin
   *  categoría, sin zona y con el problema en "(pendiente)". */
  private Lead newChatLead(String tokenSuffix) {
    Lead lead = new Lead();
    lead.setProblem("(pendiente)");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.NEW);
    lead.setUrgency("baja");
    lead.setReadyForMatching(false);
    lead.setNotes("");
    lead.setAccessToken("tok-unclassified-" + tokenSuffix + "-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  @Test
  void unclassifiedRequest_isEscalatedInsteadOfAskingTheSameQuestionTwice() throws Exception {
    Lead lead = newChatLead("carpintero");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    // Turno 1: el oficio no está en el catálogo → repregunta honesta, una vez.
    leadMessageService.postFromCustomer(leadId, token, "Necesito un carpintero");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    String first = lastAgentText(leadId);
    assertThat(first).as("el primer turno sí pregunta")
        .isEqualTo(LeadAgentService.ASK_WHAT_HAPPENED);

    // Turno 2: el vecino contesta y sigue sin entenderse. Acá estaba el bucle.
    leadMessageService.postFromCustomer(leadId, token, "Necesito arreglar un sillón");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    String second = lastAgentText(leadId);
    assertThat(second).as("no se repite la misma pregunta")
        .isNotEqualTo(LeadAgentService.ASK_WHAT_HAPPENED);
    assertThat(second).as("se pasa a una persona").contains("Te paso con una persona de Fixy");

    // El pedido deja de ser invisible: queda escrito con las palabras del vecino.
    Lead saved = leadRepository.findById(leadId).orElseThrow();
    assertThat(saved.getProblem()).as("el problema ya no es (pendiente)").isNotEqualTo("(pendiente)");
    assertThat(saved.getProblem()).contains("carpintero").contains("sillón");

    verify(telegramNotifyService, times(1)).notifyEscalation(any(Lead.class), anyString(), anyString());
  }

  @Test
  void unclassifiedRequest_staysSilentOnceAlreadyEscalated() throws Exception {
    Lead lead = newChatLead("flete");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    leadMessageService.postFromCustomer(leadId, token, "Hola necesito un flete");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    leadMessageService.postFromCustomer(leadId, token, "Necesito un flete para una mudanza");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    long beforeAgentMessages = agentMessageCount(leadId);

    // Tercera insistencia: ya se escaló, no hay nada nuevo que decir.
    leadMessageService.postFromCustomer(leadId, token, "hola? seguís ahí?");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    assertThat(agentMessageCount(leadId))
        .as("no vuelve a hablar tras escalar").isEqualTo(beforeAgentMessages);
    verify(telegramNotifyService, times(1)).notifyEscalation(any(Lead.class), anyString(), anyString());
  }

  /**
   * El tráfico sintético no puede despertar a nadie. Antes de este cambio el
   * guard [smoke] de la escalación era inútil en el chat: leía problem, que en
   * un lead de chat vale "(pendiente)" hasta que se clasifica.
   */
  @Test
  void smokeChatLead_neverReachesTelegram() throws Exception {
    Lead lead = newChatLead("smoke");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    leadMessageService.postFromCustomer(leadId, token, "[smoke] necesito un carpintero");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    leadMessageService.postFromCustomer(leadId, token, "[smoke] arreglar un sillón");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    assertThat(leadRepository.findById(leadId).orElseThrow().getProblem())
        .as("la marca se preserva para los demás guards").startsWith("[smoke]");
    verify(telegramNotifyService, never())
        .notifyEscalation(any(Lead.class), anyString(), anyString());
  }

  private String lastAgentText(Long leadId) {
    List<LeadMessage> all = messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    for (int i = all.size() - 1; i >= 0; i--) {
      if ("fixy".equals(all.get(i).getSender())) {
        return all.get(i).getText();
      }
    }
    return null;
  }

  private long agentMessageCount(Long leadId) {
    return messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .count();
  }
}
