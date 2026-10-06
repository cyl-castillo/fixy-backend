package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixy.backend.core.agent.AgentTurnRecorder;
import com.fixy.backend.core.agent.LlmGateway;
import com.fixy.backend.core.cases.AgentTurn;
import com.fixy.backend.core.cases.AgentTurnRepository;
import com.fixy.backend.dto.IntakeRequest;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Core Fase 3 (CORE_FASE3_CONTRATO.md, pieza 3): cada llamada al LLM —o la decisión de no
 * llamarlo— queda como una fila de agent_turns con su veredicto. El gateway está mockeado: se
 * ejercita el loop real (guards, fallbacks, escalamiento) y se verifica la fila por lead.
 *
 * <p>Las aserciones filtran SIEMPRE por el lead_id propio del test (o por un marcador único en
 * user_content para el intake sin lead): el contexto de Spring comparte la base con otros hilos
 * ({@code @Async}), nunca se cuenta el total de la tabla. Sin {@code @Transactional}: el turno
 * corre en otro hilo (mismo motivo que LeadAgentFallbackRepeatTest); el contexto es propio de esta
 * clase (por el {@code @MockitoBean}), así que sus filas no contaminan a otros tests.
 */
@SpringBootTest
class AgentTurnRecordingTest {

  @MockitoBean private LlmGateway llmGateway;
  @MockitoSpyBean private AgentTurnRepository agentTurnRepository;

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private AgentService agentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private LeadRepository leadRepository;

  private static final String QUESTION_ZONE = "Dale, anotado. ¿En qué zona estás?";

  @BeforeEach
  void gatewayEncendido() {
    when(llmGateway.isEnabled()).thenReturn(true);
    when(llmGateway.provider()).thenReturn("openai");
    when(llmGateway.model()).thenReturn("modelo-de-prueba");
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private Lead lead(String category, String location) {
    Lead lead = new Lead();
    lead.setProblem("[smoke] pedido de prueba de agent_turns");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.NEW);
    lead.setAccessToken("tok-agent-turns-" + System.nanoTime());
    lead.setDetectedCategory(category);
    lead.setLocation(location);
    return leadRepository.save(lead);
  }

  private void customerSays(Lead lead, String text) {
    leadMessageService.postFromCustomer(lead.getId(), lead.getAccessToken(), text);
  }

  private List<AgentTurn> rows(Long leadId, String kind) {
    return agentTurnRepository.findByLeadIdOrderByCreatedAtAscIdAsc(leadId).stream()
        .filter(t -> t.getKind().equals(kind))
        .toList();
  }

  private AgentTurn singleTurn(Long leadId) {
    await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
        .until(() -> !rows(leadId, AgentTurn.KIND_TURN).isEmpty());
    List<AgentTurn> turns = rows(leadId, AgentTurn.KIND_TURN);
    assertThat(turns).as("exactamente una fila TURN por turno").hasSize(1);
    return turns.get(0);
  }

  private AgentTurn runTurn(Lead lead) {
    leadAgentService.respondToCustomerAsync(lead.getId());
    return singleTurn(lead.getId());
  }

  private void stubTurnReply(String raw) {
    when(llmGateway.completeJson(any(), any(), any())).thenReturn(raw);
  }

  private List<String> agentMessages(Long leadId) {
    return leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .map(LeadMessage::getText)
        .toList();
  }

  private static String turnJson(String reply, String extracted, String action) {
    return "{\"reply\":\"" + reply + "\",\"extracted\":" + extracted + ",\"action\":" + action + "}";
  }

  private static final String NO_ACTION = "{\"type\":\"none\",\"reason\":null,\"summary\":null}";

  // ---------------------------------------------------------------------------------------------
  // TURN: un test por outcome alcanzable
  // ---------------------------------------------------------------------------------------------

  @Test
  void llmOk_registraLaFilaConProviderModeloVersionYContenido() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] se me rompió una canilla");
    String raw = turnJson(QUESTION_ZONE, "{\"category\":\"plomeria\",\"zone\":null}", NO_ACTION);
    stubTurnReply(raw);

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_OK);
    assertThat(turn.getProvider()).isEqualTo("openai");
    assertThat(turn.getModel()).isEqualTo("modelo-de-prueba");
    assertThat(turn.getPromptVersion()).matches("[0-9a-f]{12}");
    assertThat(turn.getUserContent()).contains("se me rompió una canilla");
    assertThat(turn.getRawOutput()).isEqualTo(raw);
    assertThat(turn.getLatencyMs()).isGreaterThanOrEqualTo(0);
    assertThat(turn.getGuards()).isEmpty();
    assertThat(turn.getCreatedAt()).isNotNull();
    assertThat(turn.getLeadId()).isEqualTo(lead.getId());
  }

  @Test
  void llmEmpty_cuandoElGatewayDevuelveNull() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] se me rompió una canilla");
    stubTurnReply(null);

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_EMPTY);
    assertThat(turn.getRawOutput()).isNull();
    assertThat(turn.getProvider()).isEqualTo("openai");
    // El fallback heurístico igual contestó.
    await().atMost(Duration.ofSeconds(10)).until(() -> !agentMessages(lead.getId()).isEmpty());
  }

  @Test
  void llmNoReply_cuandoHayJsonPeroSinReply() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] se me rompió una canilla");
    String raw = "{\"extracted\":{\"category\":\"plomeria\"},\"action\":" + NO_ACTION + "}";
    stubTurnReply(raw);

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_NO_REPLY);
    assertThat(turn.getRawOutput()).isEqualTo(raw);
  }

  @Test
  void llmRawPosted_cuandoNoHayJsonSePosteaElTextoCrudo() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] se me rompió una canilla");
    String raw = "Dale, ¿en qué zona estás?";
    stubTurnReply(raw);

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_RAW_POSTED);
    assertThat(turn.getRawOutput()).isEqualTo(raw);
    assertThat(agentMessages(lead.getId())).contains(raw);
  }

  @Test
  void fallbackRepeat_cuandoElLlmRepiteSuMensajeAnterior() {
    Lead lead = lead(null, null);
    leadMessageService.postFromAgent(lead.getId(), "¿Qué tipo de arreglo necesitás?");
    customerSays(lead, "[smoke] una pérdida en la canilla");
    stubTurnReply(turnJson("¿Qué tipo de arreglo necesitás?", "{}", NO_ACTION));

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_FALLBACK_REPEAT);
    // El cliente contestó corto a una pregunta del agente: la captura determinista anota la respuesta.
    assertThat(turn.getGuards()).contains(AgentTurn.GUARD_SHORT_ANSWER_RECORDED);
  }

  @Test
  void fallbackSearchingClaim_cuandoDiceQueBuscaYaTeniendoProveedorEncima() {
    Lead lead = lead("plomeria", "Solymar");
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    lead.setAssignedProviderId(999_001L);
    lead.setAssignedProvider("Proveedor de prueba");
    lead = leadRepository.save(lead);
    customerSays(lead, "[smoke] hola, alguna novedad");
    stubTurnReply(turnJson("Estoy buscando un proveedor para vos.", "{}", NO_ACTION));

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_FALLBACK_SEARCHING_CLAIM);
  }

  @Test
  void fallbackZoneQuestion_cuandoLaRespuestaNoPideLaZonaUnicaTraba() {
    Lead lead = lead("plomeria", null);
    customerSays(lead, "[smoke] sigue la pérdida");
    stubTurnReply(turnJson("Dale, anotado.", "{}", NO_ACTION));

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_FALLBACK_ZONE_QUESTION);
  }

  @Test
  void llmEscalated_tienePrecedenciaSobreLlmOkAunqueLaRespuestaSePostee() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] quiero hablar con una persona, esto no se arregla");
    stubTurnReply(turnJson("Te paso con una persona del equipo. ¿En qué zona estás?", "{}",
        "{\"type\":\"escalate\",\"reason\":\"el cliente pide una persona\",\"summary\":\"pide hablar con alguien\"}"));

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_ESCALATED);
    assertThat(agentMessages(lead.getId())).anyMatch(m -> m.contains("Te paso con una persona del equipo"));
  }

  @Test
  void errorFallback_cuandoElTurnoLanzaExcepcion() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] se me rompió una canilla");
    when(llmGateway.completeJson(any(), any(), any())).thenThrow(new IllegalStateException("el gateway explotó"));

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_ERROR_FALLBACK);
    assertThat(turn.getRawOutput()).isNull();
  }

  @Test
  void deterministic_elAckEnEsperaNoLlamaAlLlmYQuedaRegistrado() {
    Lead lead = lead("plomeria", "Solymar");
    lead.setReadyForMatching(true);
    lead = leadRepository.save(lead);
    customerSays(lead, "gracias");

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_DETERMINISTIC);
    assertThat(turn.getProvider()).isEqualTo("none");
    assertThat(turn.getModel()).isNull();
    assertThat(turn.getRawOutput()).isNull();
    assertThat(turn.getLatencyMs()).isBetween(0, 100);
    assertThat(turn.getUserContent()).isEqualTo("gracias");
    verify(llmGateway, never()).completeJson(any(), any(), any());
  }

  @Test
  void deterministic_laPreguntaDePrecioConCategoriaConocidaNoLlamaAlLlm() {
    Lead lead = lead("plomeria", null);
    customerSays(lead, "[smoke] ¿cuánto sale?");

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_DETERMINISTIC);
    assertThat(turn.getProvider()).isEqualTo("none");
    assertThat(turn.getUserContent()).contains("cuánto sale");
    verify(llmGateway, never()).completeJson(any(), any(), any());
  }

  // ---------------------------------------------------------------------------------------------
  // TURN: guards
  // ---------------------------------------------------------------------------------------------

  @Test
  void guard_zoneDiscarded_cuandoElLlmExtraeUnaZonaQueElClienteNoDijo() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] se me rompió una canilla");
    stubTurnReply(turnJson(QUESTION_ZONE, "{\"category\":\"plomeria\",\"zone\":\"Lagomar\"}", NO_ACTION));

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_OK);
    assertThat(turn.getGuards().split(",")).contains(AgentTurn.GUARD_ZONE_DISCARDED);
  }

  @Test
  void guard_categoryDiscarded_cuandoElLlmExtraeUnaCategoriaSinRastroDelCliente() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] hola buenas");
    stubTurnReply(turnJson("Hola, ¿qué necesitás?", "{\"category\":\"pasteleria\"}", NO_ACTION));

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getGuards().split(",")).contains(AgentTurn.GUARD_CATEGORY_DISCARDED);
  }

  @Test
  void guard_messageSignalsOverride_cuandoElMensajePisaLaExtraccionDelLlm() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] necesito un plomero");
    stubTurnReply(turnJson(QUESTION_ZONE, "{}", NO_ACTION));

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getGuards().split(",")).contains(AgentTurn.GUARD_MESSAGE_SIGNALS_OVERRIDE);
  }

  @Test
  void guard_contactPhoneAskAppended_cuandoSeAnexaElPedidoDeWhatsApp() {
    Lead lead = lead("plomeria", "Solymar");
    customerSays(lead, "[smoke] sigue la pérdida");
    stubTurnReply(turnJson("Dale, ya lo tengo anotado.", "{}", NO_ACTION));

    AgentTurn turn = runTurn(lead);

    assertThat(turn.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_OK);
    assertThat(turn.getGuards().split(",")).contains(AgentTurn.GUARD_CONTACT_PHONE_ASK_APPENDED);
    assertThat(agentMessages(lead.getId())).anyMatch(m -> m.contains("WhatsApp"));
  }

  // ---------------------------------------------------------------------------------------------
  // El recorder nunca rompe el turno
  // ---------------------------------------------------------------------------------------------

  @Test
  void unRepositorioQueLanzaNoRompeElTurno() {
    Lead lead = lead(null, null);
    customerSays(lead, "[smoke] se me rompió una canilla");
    stubTurnReply(turnJson(QUESTION_ZONE, "{\"category\":\"plomeria\"}", NO_ACTION));
    doThrow(new IllegalStateException("la base no responde")).when(agentTurnRepository).save(any(AgentTurn.class));
    try {
      leadAgentService.respondToCustomerAsync(lead.getId());

      // El turno igual contestó y aplicó los campos; solo faltó la fila de agent_turns.
      await().atMost(Duration.ofSeconds(20)).until(() -> agentMessages(lead.getId()).contains(QUESTION_ZONE));
      await().atMost(Duration.ofSeconds(10)).until(() ->
          "plomeria".equals(leadRepository.findById(lead.getId()).orElseThrow().getDetectedCategory()));
    } finally {
      reset(agentTurnRepository);
    }
    assertThat(rows(lead.getId(), AgentTurn.KIND_TURN)).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // INTAKE
  // ---------------------------------------------------------------------------------------------

  private List<AgentTurn> intakeRowsWith(String marker) {
    return agentTurnRepository.findAll().stream()
        .filter(t -> AgentTurn.KIND_INTAKE.equals(t.getKind()) && t.getUserContent().contains(marker))
        .toList();
  }

  private static String marker() {
    return "marca-intake-" + System.nanoTime();
  }

  @Test
  void intakeHeuristic_sinLlmConfiguradoConLeadIdNull() {
    String marker = marker();
    // El gateway mockeado: provider openai sin API key => classify cae a la heurística.
    when(llmGateway.hasOpenAiKey()).thenReturn(false);

    agentService.classify(new IntakeRequest("[smoke] se rompió la canilla " + marker, null, null, "web"));

    List<AgentTurn> rows = intakeRowsWith(marker);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getOutcome()).isEqualTo(AgentTurn.OUTCOME_HEURISTIC);
    assertThat(rows.get(0).getLeadId()).isNull();
    assertThat(rows.get(0).getProvider()).isEqualTo("none");
    assertThat(rows.get(0).getPromptVersion()).matches("[0-9a-f]{12}");
  }

  @Test
  void intakeHeuristic_conLeadId() {
    Lead lead = lead(null, null);
    String marker = marker();

    agentService.classify(new IntakeRequest("[smoke] se rompió la canilla " + marker, null, null, "web"), lead.getId());

    List<AgentTurn> rows = rows(lead.getId(), AgentTurn.KIND_INTAKE);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getOutcome()).isEqualTo(AgentTurn.OUTCOME_HEURISTIC);
  }

  @Test
  void intakeLlmOk_conYSinLeadId() {
    when(llmGateway.hasOpenAiKey()).thenReturn(true);
    when(llmGateway.completeTextOpenAi(any())).thenReturn("""
        {"leadType":"cliente","serviceCategory":"plomeria","area":"Solymar","urgency":"media",
         "summary":"canilla rota","missingFields":["foto del problema"],"suggestedReply":"Gracias."}""");
    Lead lead = lead(null, null);
    String sin = marker();
    String con = marker();

    agentService.classify(new IntakeRequest("[smoke] canilla rota " + sin, null, null, "web"));
    agentService.classify(new IntakeRequest("[smoke] canilla rota " + con, null, null, "web"), lead.getId());

    AgentTurn sinLead = intakeRowsWith(sin).get(0);
    assertThat(sinLead.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_OK);
    assertThat(sinLead.getLeadId()).isNull();
    assertThat(sinLead.getProvider()).isEqualTo("openai");
    assertThat(sinLead.getRawOutput()).contains("canilla rota");
    List<AgentTurn> conLead = rows(lead.getId(), AgentTurn.KIND_INTAKE);
    assertThat(conLead).hasSize(1);
    assertThat(conLead.get(0).getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_OK);
  }

  @Test
  void intakeLlmEmptyYLlmUnparseable() {
    when(llmGateway.hasOpenAiKey()).thenReturn(true);
    String vacio = marker();
    String roto = marker();

    when(llmGateway.completeTextOpenAi(any())).thenReturn(null);
    agentService.classify(new IntakeRequest("[smoke] canilla " + vacio, null, null, "web"));
    when(llmGateway.completeTextOpenAi(any())).thenReturn("esto no es json");
    agentService.classify(new IntakeRequest("[smoke] canilla " + roto, null, null, "web"));

    assertThat(intakeRowsWith(vacio)).singleElement()
        .satisfies(t -> assertThat(t.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_EMPTY));
    assertThat(intakeRowsWith(roto)).singleElement().satisfies(t -> {
      assertThat(t.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_UNPARSEABLE);
      assertThat(t.getRawOutput()).isEqualTo("esto no es json");
    });
  }

  // ---------------------------------------------------------------------------------------------
  // GREETING
  // ---------------------------------------------------------------------------------------------

  @Test
  void greetingLlmOkYLlmEmpty() {
    Lead conRespuesta = lead("plomeria", "Solymar");
    when(llmGateway.completeText(any(), any())).thenReturn("Hola, soy Fixy. Ya tengo tu pedido.");
    leadAgentService.greet(conRespuesta);
    await().atMost(Duration.ofSeconds(20)).until(() -> !rows(conRespuesta.getId(), AgentTurn.KIND_GREETING).isEmpty());
    assertThat(rows(conRespuesta.getId(), AgentTurn.KIND_GREETING)).singleElement().satisfies(t -> {
      assertThat(t.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_OK);
      assertThat(t.getRawOutput()).isEqualTo("Hola, soy Fixy. Ya tengo tu pedido.");
      assertThat(t.getPromptVersion()).matches("[0-9a-f]{12}");
    });

    Lead sinRespuesta = lead("plomeria", "Solymar");
    when(llmGateway.completeText(any(), any())).thenReturn(null);
    leadAgentService.greet(sinRespuesta);
    await().atMost(Duration.ofSeconds(20)).until(() -> !rows(sinRespuesta.getId(), AgentTurn.KIND_GREETING).isEmpty());
    assertThat(rows(sinRespuesta.getId(), AgentTurn.KIND_GREETING)).singleElement()
        .satisfies(t -> assertThat(t.getOutcome()).isEqualTo(AgentTurn.OUTCOME_LLM_EMPTY));
  }

  // ---------------------------------------------------------------------------------------------
  // Recorder: truncado, idempotencia, latencia
  // ---------------------------------------------------------------------------------------------

  @Test
  void elRecorderTruncaA16000SinQueLaBaseLanceYLaLatenciaNoEsNegativa() {
    Lead lead = lead(null, null);
    AgentTurnRecorder recorder = new AgentTurnRecorder(agentTurnRepository, llmGateway);
    String enorme = "x".repeat(20_000);

    AgentTurnRecorder.Handle handle = recorder.start(AgentTurn.KIND_TURN, lead.getId(), "abcdef012345", enorme);
    recorder.finish(handle, enorme, AgentTurn.OUTCOME_LLM_OK, List.of("a", "b"));

    List<AgentTurn> rows = rows(lead.getId(), AgentTurn.KIND_TURN);
    assertThat(rows).singleElement().satisfies(t -> {
      assertThat(t.getUserContent()).hasSize(16_000);
      assertThat(t.getRawOutput()).hasSize(16_000);
      assertThat(t.getLatencyMs()).isGreaterThanOrEqualTo(0);
      assertThat(t.getGuards()).isEqualTo("a,b");
    });
  }

  @Test
  void finishEsIdempotenteYUnHandleNullNoHaceNada() {
    Lead lead = lead(null, null);
    AgentTurnRecorder recorder = new AgentTurnRecorder(agentTurnRepository, llmGateway);

    AgentTurnRecorder.Handle handle = recorder.start(AgentTurn.KIND_TURN, lead.getId(), "abcdef012345", "hola");
    recorder.finish(handle, "uno", AgentTurn.OUTCOME_LLM_OK, List.of());
    recorder.finish(handle, "dos", AgentTurn.OUTCOME_LLM_EMPTY, List.of());
    recorder.finish(null, "tres", AgentTurn.OUTCOME_LLM_OK, null);

    assertThat(rows(lead.getId(), AgentTurn.KIND_TURN)).singleElement()
        .satisfies(t -> assertThat(t.getRawOutput()).isEqualTo("uno"));
  }

  @Test
  void elRecorderNoopTolerantNullEnTodo() {
    AgentTurnRecorder noop = AgentTurnRecorder.noop();
    AgentTurnRecorder.Handle handle = noop.start(null, null, null, null);
    handle.stopClock();
    noop.finish(handle, null, null, null);
    noop.finish(handle, null, null, null);
  }
}
