package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

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
 * BUG B de las guardias diarias del 2026-09-02 y 2026-09-03: la CLASE general
 * del bucle del lead #265 — <b>pregunto X → el vecino contesta → repregunto X
 * idéntico</b>.
 *
 * <pre>
 * 23:00:11 fixy     Anotado: problema de aire acondicionado. ¿En qué zona estás?
 * 23:00:20 cliente  pocitos
 * 23:00:38 fixy     Anotado: problema de aire acondicionado. ¿En qué zona estás?
 * </pre>
 *
 * {@code c338bcb} cerró ese camino concreto (zona no reconocida → se dice la
 * cobertura real), pero la guardia del 03/09 verificó en código que la clase
 * seguía destapada: el guard anti-loro de {@code LeadMessageService} comparaba
 * contra el último mensaje del CHAT, y el "pocitos" del cliente en el medio lo
 * desactivaba. Cualquier otro dato que el vecino conteste y el sistema no sepa
 * parsear cae en el mismo pozo — acá se ejercita con la pregunta por la
 * dirección exacta, que el fallback determinista rehace igual porque sin LLM
 * la dirección nunca se extrae.
 *
 * Montaje idéntico al de {@link LeadAgentUnknownZoneLoopTest}: agente
 * encendido y LLM sin credenciales, que es exactamente lo que tuvo prod.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token=",
    "fixy.reengagement.enabled=false"
})
class LeadAgentAnsweredQuestionLoopTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository messageRepository;
  @Autowired private LeadRepository leadRepository;

  @MockitoBean private TelegramNotifyService telegramNotifyService;

  private Lead newChatLead(String tokenSuffix) {
    Lead lead = new Lead();
    lead.setProblem("(pendiente)");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.NEW);
    lead.setLocation("Solymar");
    lead.setUrgency("baja");
    lead.setReadyForMatching(false);
    lead.setNotes("");
    lead.setAccessToken("tok-answered-" + tokenSuffix + "-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  @Test
  void questionAlreadyAnsweredIsNotAskedAgainWordForWord() throws Exception {
    Lead lead = newChatLead("direccion");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    leadMessageService.postFromCustomer(leadId, token, "necesito que me armen un ropero");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    List<String> afterFirstTurn = agentTexts(leadId);
    assertThat(afterFirstTurn).as("el primer turno sí contesta").isNotEmpty();
    assertThat(afterFirstTurn.get(afterFirstTurn.size() - 1))
        .as("y lo que pregunta es la dirección, el dato que falta")
        .contains("dirección exacta");

    // El vecino CONTESTA. Acá estaba el bucle: sin LLM la dirección nunca se
    // extrae, el builder del ack rehace la misma frase carácter por carácter,
    // y el guard viejo no la veía porque en el medio está el mensaje del
    // cliente. La dirección la dio: no se le puede volver a pedir.
    leadMessageService.postFromCustomer(leadId, token, "Av. Giannattasio 25500 esquina Becú");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    assertThat(agentTexts(leadId))
        .as("la pregunta que el vecino acaba de contestar no se le repite")
        .isEqualTo(afterFirstTurn);
  }

  /** El vecino insiste tres veces: el agente no puede volverse un loro. */
  @Test
  void insistingNeverProducesTheSameAnswerTwiceInARow() throws Exception {
    Lead lead = newChatLead("insiste");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    for (String message : List.of("necesito que me armen un ropero",
        "Av. Giannattasio 25500", "y? me lo pueden armar?")) {
      leadMessageService.postFromCustomer(leadId, token, message);
      leadAgentService.respondToCustomerAsync(leadId);
      Thread.sleep(1500);
    }

    List<String> texts = agentTexts(leadId);
    for (int i = 1; i < texts.size(); i++) {
      assertThat(texts.get(i))
          .as("mensaje %d del agente repite el anterior", i)
          .isNotEqualTo(texts.get(i - 1));
    }
  }

  private List<String> agentTexts(Long leadId) {
    return messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .map(LeadMessage::getText)
        .toList();
  }
}
