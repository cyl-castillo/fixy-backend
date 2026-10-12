package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fixy.backend.core.agent.LlmGateway;
import com.fixy.backend.core.cases.AgentTurn;
import com.fixy.backend.core.cases.AgentTurnRepository;
import com.fixy.backend.core.domain.DomainCatalog;
import com.fixy.backend.domain.fixy.HomeServicesPolicy;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Lead #276 (2026-10-09): el vecino escribió "Necesito cambiar la garrafa" (cuando "garrafa" todavía
 * no era keyword de ninguna categoría), el LLM extrajo {@code mandados} cuatro turnos seguidos, el
 * guard anti-alucinación lo descartó —correctamente— y el lead quedó SIN categoría una hora mientras
 * el agente decía "lo gestiono ahora y te aviso en cuanto alguien tome el pedido" (nadie buscaba).
 *
 * <p>Regla nueva: si el turno termina sin categoría matcheable y el LLM promete gestión, no se
 * postea la promesa sino la pregunta determinista de rubro. El guard de categoría NO se toca.
 *
 * <p>Gateway mockeado, loop real. Las aserciones filtran por el lead propio del test; el turno corre
 * en otro hilo, así que sin {@code @Transactional} (mismo patrón que AgentTurnRecordingTest).
 */
@SpringBootTest
class LeadAgentPromiseWithoutCategoryTest {

  @MockitoBean private LlmGateway llmGateway;

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private LeadRepository leadRepository;
  @Autowired private AgentTurnRepository agentTurnRepository;

  private static final String PROMISE = "Perfecto, lo gestiono ahora y te aviso en cuanto alguien tome el pedido";
  private static final String NO_ACTION = "{\"type\":\"none\",\"reason\":null,\"summary\":null}";
  /** Sin keyword de ninguna categoría: lo que hacía el 8B en #276 era presumirle mandados. */
  private static final String VAGUE = "[smoke] necesito que alguien me dé una mano con unas cosas";

  @BeforeEach
  void gatewayEncendido() {
    when(llmGateway.isEnabled()).thenReturn(true);
    when(llmGateway.provider()).thenReturn("openai");
    when(llmGateway.model()).thenReturn("modelo-de-prueba");
  }

  // zona y teléfono ya conocidos: el único hueco del pedido es la categoría (como #276 tras los primeros turnos)
  private Lead lead() {
    Lead lead = new Lead();
    lead.setProblem("[smoke] pedido de prueba de promesa sin categoría");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.NEW);
    lead.setAccessToken("tok-promesa-sin-cat-" + System.nanoTime());
    lead.setLocation("Lomas de Solymar");
    lead.setPhone("099111222");
    return leadRepository.save(lead);
  }

  private void customerSays(Lead lead, String text) {
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), text);
  }

  private static String turnJson(String reply, String category) {
    return "{\"reply\":\"" + reply + "\",\"extracted\":{\"category\":" + (category == null ? "null" : "\"" + category + "\"")
        + ",\"zone\":null},\"action\":" + NO_ACTION + "}";
  }

  private List<AgentTurn> turns(Long leadId) {
    return agentTurnRepository.findByLeadIdOrderByCreatedAtAscIdAsc(leadId).stream()
        .filter(t -> AgentTurn.KIND_TURN.equals(t.getKind()))
        .toList();
  }

  private AgentTurn awaitTurn(Long leadId, int n) {
    await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
        .until(() -> turns(leadId).size() >= n);
    return turns(leadId).get(n - 1);
  }

  private List<String> agentMessages(Long leadId) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .map(LeadMessage::getText)
        .toList();
  }

  private String categoryOf(Long leadId) {
    return leadRepository.findById(leadId).orElseThrow().getDetectedCategory();
  }

  @Test
  void promesaSinCategoria_sePosteaLaPreguntaDeRubroConLaDescartadaPropuesta() {
    assertThat(DomainCatalog.get().detectCategory(VAGUE)).as("precondición: el texto no trae rubro").isEmpty();
    Lead lead = lead();
    customerSays(lead, VAGUE);
    when(llmGateway.completeJson(any(), any(), any())).thenReturn(turnJson(PROMISE, "mandados"));

    leadAgentService.respondToCustomerAsync(lead.getId());
    AgentTurn turn = awaitTurn(lead.getId(), 1);

    // se postea la pregunta determinista, NO la promesa
    assertThat(agentMessages(lead.getId()))
        .containsExactly(HomeServicesPolicy.askCategoryReply("mandados"));
    assertThat(agentMessages(lead.getId()).get(0)).startsWith("Me parece que es *").contains("Si no, decime si es");
    // el guard anti-alucinación se queda como está: la categoría NO se fija
    assertThat(categoryOf(lead.getId())).isNull();
    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_FALLBACK_NO_CATEGORY);
    assertThat(turn.getGuards())
        .contains(AgentTurn.GUARD_CATEGORY_DISCARDED)
        .contains(AgentTurn.GUARD_PROMISE_WITHOUT_CATEGORY);
    assertThat(turn.getRawOutput()).contains("lo gestiono ahora");
  }

  @Test
  void promesaSinCategoriaNiDescartada_sePosteaLaPreguntaGenericaDeRubro() {
    Lead lead = lead();
    customerSays(lead, VAGUE);
    when(llmGateway.completeJson(any(), any(), any())).thenReturn(turnJson(PROMISE, null));

    leadAgentService.respondToCustomerAsync(lead.getId());
    AgentTurn turn = awaitTurn(lead.getId(), 1);

    assertThat(agentMessages(lead.getId())).containsExactly(HomeServicesPolicy.askCategoryReply(null));
    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_FALLBACK_NO_CATEGORY);
    assertThat(turn.getGuards())
        .contains(AgentTurn.GUARD_PROMISE_WITHOUT_CATEGORY)
        .doesNotContain(AgentTurn.GUARD_CATEGORY_DISCARDED);
  }

  @Test
  void conCategoriaNombradaPorElVecino_seFijaYLaRespuestaDelLlmSalePorTalCual() {
    Lead lead = lead();
    customerSays(lead, "[smoke] necesito un mandado");
    when(llmGateway.completeJson(any(), any(), any())).thenReturn(turnJson(PROMISE, "mandados"));

    leadAgentService.respondToCustomerAsync(lead.getId());
    AgentTurn turn = awaitTurn(lead.getId(), 1);

    assertThat(categoryOf(lead.getId())).isEqualTo("mandados");
    // Con categoría y zona el matching arranca después y puede sumar su propio aviso: lo primero
    // que sale es la respuesta del LLM sin tocar, nunca la pregunta de rubro.
    assertThat(agentMessages(lead.getId())).first().isEqualTo(PROMISE);
    assertThat(agentMessages(lead.getId())).noneMatch(m -> m.startsWith("Me parece que es *"));
    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_OK);
    assertThat(turn.getGuards())
        .doesNotContain(AgentTurn.GUARD_PROMISE_WITHOUT_CATEGORY)
        .doesNotContain(AgentTurn.GUARD_CATEGORY_DISCARDED);
  }

  @Test
  void sinPromesa_aunSinCategoria_laRespuestaSalePorTalCual() {
    Lead lead = lead();
    customerSays(lead, VAGUE);
    String reply = "Dale. Contame un poco más, ¿qué necesitás?";
    when(llmGateway.completeJson(any(), any(), any())).thenReturn(turnJson(reply, null));

    leadAgentService.respondToCustomerAsync(lead.getId());
    AgentTurn turn = awaitTurn(lead.getId(), 1);

    assertThat(agentMessages(lead.getId())).containsExactly(reply);
    assertThat(categoryOf(lead.getId())).isNull();
    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_OK);
    assertThat(turn.getGuards()).doesNotContain(AgentTurn.GUARD_PROMISE_WITHOUT_CATEGORY);
  }

  @Test
  void laPreguntaDeRubroNoSeRepite_sinRespuestaDelVecinoSeEscalaYNoSePostea() {
    Lead lead = lead();
    customerSays(lead, VAGUE);
    when(llmGateway.completeJson(any(), any(), any())).thenReturn(turnJson(PROMISE, "mandados"));
    leadAgentService.respondToCustomerAsync(lead.getId());
    awaitTurn(lead.getId(), 1);

    // el vecino contesta sin nombrar rubro y el LLM vuelve a prometer
    customerSays(lead, "[smoke] no sé, lo que te dije recién");
    leadAgentService.respondToCustomerAsync(lead.getId());
    AgentTurn second = awaitTurn(lead.getId(), 2);

    assertThat(second.getOutcome()).isEqualTo(AgentTurn.OUTCOME_FALLBACK_NO_CATEGORY);
    assertThat(second.getGuards())
        .contains(AgentTurn.GUARD_PROMISE_WITHOUT_CATEGORY)
        .contains(AgentTurn.GUARD_HEURISTIC_ESCALATED);
    // la misma pregunta de rubro no salió dos veces, y la promesa nunca salió
    assertThat(agentMessages(lead.getId()))
        .filteredOn(m -> m.startsWith("Me parece que es *"))
        .hasSize(1);
    assertThat(agentMessages(lead.getId())).noneMatch(m -> m.contains("lo gestiono ahora"));
  }
}
