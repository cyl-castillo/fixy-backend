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
 * El bucle de la zona que Fixy no reconoce (guardia diaria 2026-09-02, lead
 * #265 de prod — el ÚNICO pedido de esas 24 h, y se murió acá).
 *
 * <pre>
 * 22:59:47 cliente  necesito darle mantenimiento a mi aire acondicionado, vivo en montevideo
 * 23:00:11 fixy     Anotado: problema de aire acondicionado. ¿En qué zona estás?
 * 23:00:20 cliente  pocitos
 * 23:00:38 fixy     Anotado: problema de aire acondicionado. ¿En qué zona estás?   <-- idéntica
 * </pre>
 *
 * El vecino contestó la pregunta y recibió la misma frase carácter por
 * carácter. "Pocitos" no existe en {@link com.fixy.backend.model.CoverageZone},
 * así que la zona no se guarda, y con {@code hasZone == false} el builder del
 * ack reconstruye la misma pregunta para siempre. El guard anti-loro de
 * {@code LeadMessageService.postFromAgent} no lo agarra: compara contra el
 * último mensaje del CHAT, y en el medio está el "pocitos" del cliente.
 *
 * Zona no reconocida ≠ zona ausente: Fixy ya tiene el concepto de fuera de
 * cobertura y nunca se disparaba. Ahora, cuando la repregunta se repetiría, se
 * dice hasta dónde llega Fixy de verdad.
 *
 * Montaje idéntico al de {@link LeadAgentUnclassifiedLoopTest}: agente
 * encendido y LLM sin credenciales, que es exactamente lo que tuvo prod
 * (el fallback determinista fue el que armó las dos frases). Sin
 * {@code @Transactional} porque respondToCustomerAsync corre @Async en otro
 * hilo; reengagement apagado para que el "¿Seguís por ahí?" no ensucie el
 * conteo de mensajes.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token=",
    "fixy.reengagement.enabled=false"
})
class LeadAgentUnknownZoneLoopTest {

  private static final String ZONE_QUESTION = "¿En qué zona estás?";

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository messageRepository;
  @Autowired private LeadRepository leadRepository;

  @MockitoBean private TelegramNotifyService telegramNotifyService;

  /** Un lead recién nacido del chat, tal cual lo crea LeadService. */
  private Lead newChatLead(String tokenSuffix) {
    Lead lead = new Lead();
    lead.setProblem("(pendiente)");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.NEW);
    lead.setUrgency("baja");
    lead.setReadyForMatching(false);
    lead.setNotes("");
    lead.setAccessToken("tok-unknownzone-" + tokenSuffix + "-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  @Test
  void zoneOutsideCoverage_getsTheTruthInsteadOfTheSameQuestionTwice() throws Exception {
    Lead lead = newChatLead("pocitos");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    // Turno 1: se reconoce el rubro y falta la zona → se pregunta, una vez.
    leadMessageService.postFromCustomer(leadId, token,
        "necesito darle mantenimiento a mi aire acondicionado, vivo en montevideo");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    String first = lastAgentText(leadId);
    assertThat(first).as("el primer turno sí pregunta la zona").contains(ZONE_QUESTION);

    // Turno 2: el vecino CONTESTA con un barrio fuera de cobertura. Acá estaba
    // el bucle: la misma frase, carácter por carácter.
    leadMessageService.postFromCustomer(leadId, token, "pocitos");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    String second = lastAgentText(leadId);

    assertThat(second).as("no se repite la pregunta que el vecino ya contestó")
        .isNotEqualTo(first);
    assertThat(second).as("se le dice la verdad: esa zona todavía no se cubre")
        .contains("todavía no la cubrimos");
    assertThat(second).as("y hasta dónde llega Fixy de verdad")
        .contains("Ciudad de la Costa").contains("Solymar");
    assertThat(second).as("sin perder el pedido que ya había contado")
        .contains("aire acondicionado");

    // Lo que contestó no se pierde: ops ve la demanda real fuera de cobertura.
    assertThat(leadRepository.findById(leadId).orElseThrow().getNotes())
        .as("la respuesta del vecino queda registrada").contains("pocitos");
  }

  /** Dicha la cobertura, insistir no la repite: una persona no dice dos veces lo mismo. */
  @Test
  void zoneOutsideCoverage_staysSilentOnceCoverageWasExplained() throws Exception {
    Lead lead = newChatLead("insiste");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    leadMessageService.postFromCustomer(leadId, token,
        "necesito mantenimiento de aire acondicionado");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    leadMessageService.postFromCustomer(leadId, token, "pocitos");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    long beforeAgentMessages = agentMessageCount(leadId);

    leadMessageService.postFromCustomer(leadId, token, "y entonces? no llegan?");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    assertThat(agentMessageCount(leadId))
        .as("no repite la cobertura que ya explicó").isEqualTo(beforeAgentMessages);
  }

  /**
   * El guard no puede comerse el camino sano: si el vecino contesta con una
   * zona que Fixy SÍ cubre, el pedido sigue su curso normal y nadie recibe un
   * mensaje de fuera de cobertura.
   */
  @Test
  void zoneInsideCoverage_neverHearsAboutCoverageLimits() throws Exception {
    Lead lead = newChatLead("solymar");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    leadMessageService.postFromCustomer(leadId, token,
        "necesito mantenimiento de aire acondicionado");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    leadMessageService.postFromCustomer(leadId, token, "Solymar");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    assertThat(leadRepository.findById(leadId).orElseThrow().getLocation())
        .as("la zona cubierta sí se guarda").isEqualTo("Solymar");
    assertThat(agentTexts(leadId))
        .as("nadie le habla de límites de cobertura")
        .noneMatch(text -> text.contains("todavía no la cubrimos"));
  }

  private String lastAgentText(Long leadId) {
    List<String> agentTexts = agentTexts(leadId);
    return agentTexts.isEmpty() ? null : agentTexts.get(agentTexts.size() - 1);
  }

  private List<String> agentTexts(Long leadId) {
    return messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .map(LeadMessage::getText)
        .toList();
  }

  private long agentMessageCount(Long leadId) {
    return agentTexts(leadId).size();
  }
}
