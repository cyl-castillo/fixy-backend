package com.fixy.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.UserLead;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.UserLeadRepository;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Genera mensajes conversacionales del agente Fixy en la conversación de un lead.
 * Llama a OpenAI cuando hay credencial; si falla o no hay key, usa templates.
 */
@Service
public class LeadAgentService {

  private static final Logger log = LoggerFactory.getLogger(LeadAgentService.class);
  private static final int HISTORY_LIMIT = 10;
  /** Salto 1 de memoria de cliente (PLAN_SUPERAPP_CLIENTE.md): cuántos leads
   * previos del mismo AppUser se resumen e inyectan al contexto. Mantenido
   * chico a propósito — es un resumen, no un volcado de historial. */
  private static final int CUSTOMER_MEMORY_LEAD_LIMIT = 3;

  private static final String SYSTEM_PROMPT = PromptLoader.load("prompts/lead-agent-system.md");

  private final ObjectMapper objectMapper;
  private final WebClient openAiClient;
  private final WebClient ollamaClient;
  private final WebClient cloudflareClient;
  private final String provider;
  private final String openAiApiKey;
  private final String openAiModel;
  private final String ollamaModel;
  private final String cloudflareAccountId;
  private final String cloudflareApiToken;
  private final String cloudflareModel;
  private final String whatsappTemplateName;
  private final String whatsappTemplateLang;
  private final String publicAppBaseUrl;
  private final boolean enabled;
  private final LeadMessageService leadMessageService;
  private final LeadRepository leadRepository;
  private final UserLeadRepository userLeadRepository;
  private final ProviderCatalogService providerCatalogService;
  private final WhatsAppService whatsappService;
  private final com.fixy.backend.repository.ProviderRepository providerRepository;
  private final LeadTimelineService leadTimelineService;
  private final AgentService agentService;
  private final TelegramNotifyService telegramNotifyService;
  private final PushNotificationService pushNotificationService;

  public LeadAgentService(
      ObjectMapper objectMapper,
      LeadMessageService leadMessageService,
      LeadRepository leadRepository,
      UserLeadRepository userLeadRepository,
      ProviderCatalogService providerCatalogService,
      WhatsAppService whatsappService,
      com.fixy.backend.repository.ProviderRepository providerRepository,
      LeadTimelineService leadTimelineService,
      AgentService agentService,
      TelegramNotifyService telegramNotifyService,
      PushNotificationService pushNotificationService,
      @Value("${fixy.openai.api-key:}") String openAiApiKey,
      @Value("${fixy.openai.model:gpt-5-mini}") String openAiModel,
      @Value("${fixy.agent.enabled:true}") boolean enabled,
      @Value("${fixy.agent.provider:openai}") String provider,
      @Value("${fixy.ollama.base-url:http://127.0.0.1:11434}") String ollamaBaseUrl,
      @Value("${fixy.ollama.model:qwen2.5:3b}") String ollamaModel,
      @Value("${fixy.cloudflare.account-id:}") String cloudflareAccountId,
      @Value("${fixy.cloudflare.api-token:}") String cloudflareApiToken,
      @Value("${fixy.cloudflare.model:@cf/meta/llama-3.3-70b-instruct-fp8-fast}") String cloudflareModel,
      @Value("${fixy.whatsapp.template-name:provider_lead_notification}") String whatsappTemplateName,
      @Value("${fixy.whatsapp.template-lang:es}") String whatsappTemplateLang,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl
  ) {
    this.whatsappService = whatsappService;
    this.providerRepository = providerRepository;
    this.leadTimelineService = leadTimelineService;
    this.agentService = agentService;
    this.telegramNotifyService = telegramNotifyService;
    this.pushNotificationService = pushNotificationService;
    this.whatsappTemplateName = whatsappTemplateName;
    this.whatsappTemplateLang = whatsappTemplateLang;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
    this.objectMapper = objectMapper;
    this.leadMessageService = leadMessageService;
    this.leadRepository = leadRepository;
    this.userLeadRepository = userLeadRepository;
    this.providerCatalogService = providerCatalogService;
    this.openAiApiKey = openAiApiKey;
    this.openAiModel = openAiModel;
    this.ollamaModel = ollamaModel;
    this.cloudflareAccountId = cloudflareAccountId;
    this.cloudflareApiToken = cloudflareApiToken;
    this.cloudflareModel = cloudflareModel;
    this.enabled = enabled;
    this.provider = provider == null ? "openai" : provider.toLowerCase().trim();
    log.info("LeadAgentService initialized: provider={} enabled={} cloudflareModel={} ollamaModel={}",
        this.provider, this.enabled, cloudflareModel, ollamaModel);
    this.openAiClient = WebClient.builder()
        .baseUrl("https://api.openai.com/v1")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.ollamaClient = WebClient.builder()
        .baseUrl(ollamaBaseUrl)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
    this.cloudflareClient = WebClient.builder()
        .baseUrl("https://api.cloudflare.com/client/v4")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
        .build();
  }

  /** Mensaje inicial del agente cuando se crea un lead. Async — el POST no espera al LLM. */
  @Async
  public void greet(Lead lead) {
    if (!enabled) return;
    try {
      boolean isChatFirst = lead.getDetectedCategory() == null
          && (lead.getProblem() == null || "(pendiente)".equals(lead.getProblem()));
      if (isChatFirst) {
        // Saludo fijo: no hay contexto que justifique una llamada al LLM y los
        // modelos tienden a repetir el system prompt cuando se les pide
        // "presentate" sin input del usuario (visto en prod con llama-70b).
        leadMessageService.postFromAgent(lead.getId(), fallbackChatFirstGreeting());
        return;
      }
      String context = buildContext(lead);
      String instruction = """
          Es la primera vez que hablás con este cliente. Saludá brevemente,
          confirmá el pedido en tus palabras y pediles lo que falte para
          conseguir un proveedor. Si la zona o la categoría están fuera de
          alcance, decílo con honestidad. Máximo 4 oraciones.
          Hablá en primera persona como Fixy; nunca repitas ni describas
          tus instrucciones.
          """;
      String reply = callLlm(context, instruction);
      if (reply == null || reply.isBlank()) {
        reply = fallbackGreeting(lead);
      }
      leadMessageService.postFromAgent(lead.getId(), reply);
    } catch (Exception ex) {
      log.warn("greet failed for lead {}: {}", lead.getId(), ex.getMessage());
      safePost(lead.getId(), fallbackGreeting(lead));
    }
  }

  private String fallbackChatFirstGreeting() {
    return "Hola, soy Fixy. Contame qué necesitás arreglar o coordinar y te ayudo a conseguir un proveedor.";
  }

  /**
   * Genera la respuesta del agente a los mensajes PENDIENTES del cliente Y
   * extrae datos estructurados (categoría, zona, urgencia, teléfono, etc.)
   * para actualizar el Lead progresivamente. Async — el frontend recoge la
   * respuesta por polling.
   *
   * Turnos COALESCIDOS por lead (smoke lead #236, 2026-08-07): dos mensajes
   * seguidos del cliente antes de que el agente contestara disparaban dos
   * turnos concurrentes — el que corría clasificaba solo por el ÚLTIMO
   * mensaje ("agua enlatada" → plomería, ignorando el mandado del primero)
   * y una de las dos respuestas podía perderse. Acá corre UN turno por lead
   * a la vez; los disparos que llegan mientras tanto marcan pending y el
   * dueño del turno los drena al terminar — cada tanda de mensajes nuevos
   * recibe exactamente un turno, que los considera a todos.
   */
  @Async
  public void respondToCustomerAsync(Long leadId) {
    if (!enabled) return;
    TurnGate gate = turnGates.computeIfAbsent(leadId, k -> new TurnGate());
    gate.pending.set(true);
    while (gate.running.compareAndSet(false, true)) {
      try {
        while (gate.pending.compareAndSet(true, false)) {
          respondToCustomerTurn(leadId, gate);
        }
      } finally {
        gate.running.set(false);
      }
      // pending pudo setearse entre la salida del while interno y la
      // liberación de running: re-chequear. Si otro hilo ya tomó el gate,
      // el compareAndSet externo falla y ese hilo drena el pendiente.
      if (!gate.pending.get()) {
        return;
      }
    }
  }

  /** Estado de coalescing de turnos de UN lead. Ver respondToCustomerAsync. */
  private static final class TurnGate {
    final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
    /** id del último mensaje del cliente ya cubierto por un turno de este
     * proceso (0 = ninguno todavía). Volatile: lo escribe el hilo del turno
     * que termina y lo lee el del siguiente. */
    volatile long lastProcessedMessageId;
  }

  /** Gates por lead. Las entradas no se remueven a propósito: borrarlas
   * reabriría la carrera que el gate cierra, y el costo es ínfimo (dos
   * booleans y un long por lead que chateó desde el último restart). */
  private final java.util.concurrent.ConcurrentHashMap<Long, TurnGate> turnGates =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** Un turno real del agente: cubre TODOS los mensajes del cliente aún no
   * procesados (no solo el último). Solo lo llama respondToCustomerAsync,
   * ya serializado por lead vía TurnGate. */
  private void respondToCustomerTurn(Long leadId, TurnGate gate) {
    Lead lead = leadRepository.findById(leadId).orElse(null);
    if (lead == null) {
      log.warn("respondToCustomer: lead {} not found", leadId);
      return;
    }
    List<LeadMessage> pendingMessages = pendingCustomerMessages(leadId, gate.lastProcessedMessageId);
    if (pendingMessages.isEmpty()) {
      // El turno anterior ya cubrió estos mensajes (drenaje del gate):
      // no hay nada nuevo que contestar y repetir sería spam.
      log.info("turno sin mensajes nuevos del cliente en lead {}: no-op", leadId);
      return;
    }
    long maxSeenId = 0;
    for (LeadMessage m : pendingMessages) {
      if (m.getId() != null && m.getId() > maxSeenId) {
        maxSeenId = m.getId();
      }
    }
    List<String> pendingTexts = pendingMessages.stream().map(LeadMessage::getText).toList();
    // Tanda completa concatenada, para los detectores por keywords que operan
    // sobre texto plano (precio, ack, "¿el cliente preguntó algo?").
    String pendingText = String.join("\n", pendingTexts);
    try {
      // Pregunta de precio con categoría ya definida: respuesta DETERMINISTA.
      // El 8B demostró en prod (lead #111) que regatea el rango aunque la
      // instrucción se lo ordene, y "¿cuánto sale?" es demasiado crítico para
      // dejarlo al azar del modelo. El camino heurístico además extrae zona/
      // urgencia del mismo mensaje y dispara el auto-match si completa datos.
      // Estado de espera (pedido ya en búsqueda, sin proveedor asignado): un
      // "ok/gracias/dale" del cliente NO se responde — un humano tampoco lo
      // haría. Antes el agente reabría el interrogatorio ante un "ok"
      // (captura de Carlos, 2026-07-16 17:47).
      if (lead.isReadyForMatching() && lead.getAssignedProviderId() == null
          && isAcknowledgment(pendingText)) {
        log.info("ack del cliente en espera, sin respuesta: lead={}", leadId);
        return;
      }
      boolean categoryKnown = lead.getDetectedCategory() != null
          && !lead.getDetectedCategory().isBlank()
          && !"otro".equalsIgnoreCase(lead.getDetectedCategory());
      if (categoryKnown && isPriceQuestion(pendingText)) {
        respondWithHeuristicFallback(leadId, lead, pendingTexts);
        return;
      }
      // Captura determinista pregunta→respuesta: si el agente preguntó algo y
      // el cliente contestó corto, se anota en notes SIN depender de que el
      // LLM lo extraiga (lead #131: "30 m" nunca llegó a notes y el guion
      // repreguntó el tamaño).
      recordShortAnswerToLastQuestion(leadId, lead);
      String provisionalCategory = null;
      if (lead.getDetectedCategory() == null || lead.getDetectedCategory().isBlank()) {
        provisionalCategory = detectCategoryFromMessages(pendingTexts);
      }
      String context = buildContext(lead, provisionalCategory);
      String history = renderHistory(leadMessageService.recentForAgent(leadId, HISTORY_LIMIT));
      AgentTurnResult result = respondAndExtractTurn(lead, context, history);
      if (result == null || result.reply() == null || result.reply().isBlank()) {
        // El LLM falló, hizo timeout, o está deshabilitado: procesamos el
        // mensaje del cliente igual con la heurística (no un enlatado ciego).
        // (Log agregado 2026-08-06: este camino era MUDO y la simulación de
        // clientes mostró turnos cayendo acá sin rastro en el log.)
        log.info("turno LLM sin respuesta utilizable en lead {}: fallback heurístico", leadId);
        respondWithHeuristicFallback(leadId, lead, pendingTexts);
        return;
      }
      if (isStuckRepeatingItself(leadId, result.reply())) {
        // El 8B genera la MISMA respuesta que su mensaje anterior (visto en
        // lead #123: repitió textual "¿instalación, servicio o reparación?"
        // tras la respuesta del cliente). LLM atascado = turno determinista.
        log.info("LLM atascado repitiéndose en lead {}: fallback heurístico", leadId);
        respondWithHeuristicFallback(leadId, lead, pendingTexts);
        return;
      }
      Map<String, String> extracted = result.extracted();
      // Guard determinista contra zonas alucinadas: el 8B extrajo dos veces
      // en prod (leads #111 y #112) una zona que solo aparecía en las
      // preguntas del PROPIO agente, aún con la regla dura en el prompt.
      // Una zona extraída solo vale si el cliente la escribió textualmente.
      // (Los guards de extracción corren ANTES de postear desde el caso
      // #138: la validación de la respuesta de abajo necesita saber si la
      // zona llegó de verdad en este turno.)
      if (extracted != null && extracted.get("zone") != null
          && !zoneMentionedByCustomer(leadId, extracted.get("zone"))) {
        extracted = new java.util.HashMap<>(extracted);
        extracted.remove("zone");
        log.info("zona extraída descartada (el cliente no la mencionó): lead={}", leadId);
      }
      // Guard determinista contra categorías alucinadas: el 8B le presumió
      // "pastelería" a clientes que solo dijeron "hola" (leads #116/#119),
      // mismo espíritu que el guard de zona de arriba. Una categoría extraída
      // solo vale si aparece (por detección de keywords o keyword suelta) en
      // texto del CLIENTE.
      if (extracted != null && extracted.get("category") != null
          && !categoryMentionedByCustomer(leadId, extracted.get("category"))) {
        extracted = new java.util.HashMap<>(extracted);
        extracted.remove("category");
        log.info("categoría extraída descartada (el cliente no dio rastro): lead={}", leadId);
      }
      // Corrección determinista TAMBIÉN en el camino LLM (prueba de Carlos
      // lead #194: "me equivoqué es aires" — gpt-5-mini no extrajo el cambio
      // y respondió eco de la categoría vieja; la corrección no puede
      // depender de que el modelo la entienda). Lo que el MENSAJE del
      // cliente dice por keywords pisa la extracción; applyExtractedFields
      // decide después si corresponde actualizar (solo pre-matching) y
      // re-dispara el matching.
      extracted = withMessageSignals(pendingTexts, extracted);
      // Guard determinista de RESPUESTA (lead #138): con categoría conocida y
      // la zona como única traba, el 8B contestó "Dale, aire acondicionado en
      // Lomas. ¿Qué tipo de servicio necesitás?" — zona alucinada en el TEXTO
      // (el guard de arriba la descarta del dato, pero el texto salía igual) y
      // repregunta de algo ya respondido, sin pedir lo único que faltaba.
      boolean zoneArrivedThisTurn = extracted != null && extracted.get("zone") != null;
      // La categoría cuenta como conocida también si recién se detectó en
      // ESTE turno (provisional por keywords o extraída y validada): en el
      // primer mensaje del chat el lead arranca vacío y categoryKnown
      // pre-turno es false — el hueco exacto por el que el guard no disparó
      // en la réplica del #138 (lead #139).
      boolean categoryKnownForReply = categoryKnown
          || provisionalCategory != null
          || (extracted != null && extracted.get("category") != null);
      if (shouldForceZoneQuestion(categoryKnownForReply, lead.getLocation(), pendingText, result.reply(), zoneArrivedThisTurn)) {
        log.info("respuesta del LLM no pide la zona (única traba) en lead {}: fallback determinista", leadId);
        respondWithHeuristicFallback(leadId, lead, pendingTexts);
        return;
      }
      String reply = result.reply();
      // Pedido de WhatsApp (mejora diaria 2026-07-28, ver CONTACT_PHONE_ASK):
      // con categoría y zona resueltas y sin teléfono, se anexa UNA vez.
      boolean zoneKnownForReply = (lead.getLocation() != null && !lead.getLocation().isBlank())
          || zoneArrivedThisTurn;
      boolean phoneArrivedThisTurn = extracted != null && extracted.get("phone") != null;
      if (shouldAskContactPhone(categoryKnownForReply, zoneKnownForReply, lead.getPhone(), phoneArrivedThisTurn, reply)
          && !contactPhoneAlreadyAsked(leadId)) {
        reply = reply + " " + CONTACT_PHONE_ASK;
      }
      leadMessageService.postFromAgent(leadId, reply);
      if (extracted != null && !extracted.isEmpty()) {
        applyExtractedFields(leadId, extracted);
      }
      if (result.action() != null && result.action().isEscalate()) {
        dispatchEscalation(leadId, result.action());
      }
    } catch (Exception ex) {
      log.warn("respondToCustomer failed for lead {}: {}", leadId, ex.getMessage());
      try {
        respondWithHeuristicFallback(leadId, lead, pendingTexts);
      } catch (Exception fallbackEx) {
        log.error("heuristic fallback also failed for lead {}: {}", leadId, fallbackEx.getMessage());
        safePost(leadId, "Contame un poco más: ¿qué te pasa o qué necesitás arreglar en tu casa?");
      }
    } finally {
      // El turno intentó cubrir esta tanda (con LLM, heurística o el enlatado
      // del catch): avanzar el marcador pase lo que pase, para que el drenaje
      // del gate no vuelva a contestar los mismos mensajes.
      if (maxSeenId > 0) {
        gate.lastProcessedMessageId = Math.max(gate.lastProcessedMessageId, maxSeenId);
      }
    }
  }

  /**
   * Mensajes del cliente que este turno debe cubrir. Con marcador (ya corrió
   * un turno de este lead en este proceso): todo mensaje del cliente más
   * nuevo que el último cubierto. Sin marcador: el bloque de cola de
   * mensajes del cliente posteriores al último mensaje del agente — y si ese
   * bloque queda vacío (carrera con greet(): el saludo async puede postearse
   * DESPUÉS del primer mensaje del cliente), se degrada al comportamiento
   * histórico de tomar el último mensaje del cliente antes que quedarse mudo.
   */
  private List<LeadMessage> pendingCustomerMessages(Long leadId, long lastProcessedId) {
    List<LeadMessage> recent = leadMessageService.recentForAgent(leadId, HISTORY_LIMIT);
    if (lastProcessedId > 0) {
      return recent.stream()
          .filter(m -> "customer".equals(m.getSender())
              && m.getText() != null && !m.getText().isBlank()
              && m.getId() != null && m.getId() > lastProcessedId)
          .toList();
    }
    java.util.ArrayList<LeadMessage> tail = new java.util.ArrayList<>();
    for (int i = recent.size() - 1; i >= 0; i--) {
      LeadMessage m = recent.get(i);
      if (!"customer".equals(m.getSender())) {
        break;
      }
      if (m.getText() != null && !m.getText().isBlank()) {
        tail.add(0, m);
      }
    }
    if (!tail.isEmpty()) {
      return tail;
    }
    for (int i = recent.size() - 1; i >= 0; i--) {
      LeadMessage m = recent.get(i);
      if ("customer".equals(m.getSender()) && m.getText() != null && !m.getText().isBlank()) {
        return List.of(m);
      }
    }
    return List.of();
  }

  /**
   * Detección de categoría sobre una tanda de mensajes pendientes,
   * reproduciendo la semántica secuencial (un turno por mensaje): el PRIMER
   * mensaje que detecta categoría gana, y uno posterior solo la pisa si trae
   * intención explícita de corrección. detectFromText sobre el texto
   * concatenado NO sirve acá: itera categorías en orden de declaración
   * (plomería antes que mandados) y el "agua" del segundo mensaje ganaría
   * sobre el "supermercado" del primero — exactamente el bug del smoke #236.
   * Package-private estático para testear sin contexto, mismo patrón que
   * shouldForceZoneQuestion.
   */
  static String detectCategoryFromMessages(List<String> messages) {
    String category = null;
    for (String message : messages) {
      String detected = com.fixy.backend.model.ServiceCategory.detectFromText(message)
          .map(com.fixy.backend.model.ServiceCategory::id)
          .orElse(null);
      if (detected == null) {
        continue;
      }
      if (category == null || isExplicitCorrection(message)) {
        category = detected;
      }
    }
    return category;
  }

  /**
   * Fallback determinista cuando el LLM no está disponible: reutiliza el
   * clasificador heurístico de AgentService (mismo que usa el intake inicial)
   * sobre los mensajes PENDIENTES del cliente (la tanda que cubre este turno,
   * no solo el último), actualiza el lead con lo que se pudo extraer, y arma
   * una respuesta que reconoce esos datos y pide solo lo que falta. Nunca
   * promete matching de proveedor sin chequear disponibilidad real.
   */
  private void respondWithHeuristicFallback(Long leadId, Lead lead, List<String> pendingMessages) {
    List<String> messages = pendingMessages;
    if (messages == null || messages.isEmpty()) {
      String last = lastCustomerMessage(leadId);
      messages = last == null ? List.of() : List.of(last);
    }
    String pendingText = String.join("\n", messages);
    boolean autoMatchAlreadyPosted = false;
    if (!pendingText.isBlank()) {
      IntakeRequest intakeRequest = new IntakeRequest(
          pendingText,
          lead.getName(),
          lead.getPhone(),
          "chat",
          lead.getDetectedCategory(),
          lead.getLocation(),
          lead.getUrgency(),
          null,
          null
      );
      IntakeResponse classified = agentService.classify(intakeRequest);
      Map<String, String> extracted = new java.util.HashMap<>();
      // Corrección del cliente ("me equivoqué, es jardinería / no, es en
      // Lagomar"): lo que dicen LOS MENSAJES de esta tanda pisa el
      // passthrough del clasificador, que devuelve la categoría/zona ya
      // conocida cuando existe (resolvedService/resolvedArea).
      // applyExtractedFields decide después si corresponde actualizar (solo
      // pre-matching).
      String messageCategory = detectCategoryFromMessages(messages);
      if (messageCategory != null) {
        extracted.put("category", messageCategory);
      } else if (classified.serviceCategory() != null && !"otro".equalsIgnoreCase(classified.serviceCategory())) {
        extracted.put("category", classified.serviceCategory());
      }
      String messageZone = null;
      String messagePhone = null;
      for (String message : messages) {
        String zone = agentService.areaMentionedIn(message);
        if (zone != null) {
          messageZone = zone; // la última mención gana, igual que en turnos secuenciales
        }
        if (messagePhone == null) {
          messagePhone = phoneMentionedIn(message);
        }
      }
      if (messageZone != null) {
        extracted.put("zone", messageZone);
      } else if (classified.area() != null && !"sin definir".equalsIgnoreCase(classified.area())) {
        extracted.put("zone", classified.area());
      }
      if (messagePhone != null) {
        extracted.put("phone", messagePhone);
      }
      if (classified.urgency() != null) {
        extracted.put("urgency", classified.urgency());
      }
      if (!extracted.isEmpty()) {
        autoMatchAlreadyPosted = applyExtractedFieldsAndReport(leadId, extracted);
      }
    }
    if (autoMatchAlreadyPosted) {
      // tryAutoMatch ya posteó el aviso de matching (con o sin proveedor
      // disponible) — postear otro mensaje de reconocimiento sería redundante.
      return;
    }
    // Releer el lead: applyExtractedFields pudo haber actualizado categoría/zona.
    Lead refreshed = leadRepository.findById(leadId).orElse(lead);
    if (isPriceQuestion(pendingText)) {
      leadMessageService.postFromAgent(leadId, heuristicPriceReply(refreshed));
      return;
    }
    leadMessageService.postFromAgent(leadId, heuristicFallbackReply(refreshed));
  }

  /**
   * Frases explícitas de corrección de categoría ("me equivoqué", "error,
   * era...", "en realidad es...", "no, mejor..."). Prueba real de Carlos
   * 2026-08-07 (lead #235): pidió mandados y su nota de voz "quiero agua en
   * el Tata" (transcripta "agua enlatada") re-clasificó el pedido a plomería
   * — la lista de un mandado SIEMPRE va a nombrar productos que coinciden
   * con keywords de otras categorías (agua, torta, pasto...). Regla nueva:
   * con categoría ya puesta, cambiarla exige intención explícita de
   * corrección; sin ella, la mención suelta de una keyword no toca nada.
   */
  private static final java.util.regex.Pattern CORRECTION_PHRASES = java.util.regex.Pattern.compile(
      "(?i)me\\s+equivoq|\\berror\\b|en\\s+realidad|quise\\s+decir|no\\s+era\\s+eso|no\\s+es\\s+eso"
          + "|no,?\\s+mejor|cambi[aá]\\w*\\s+(la\\s+)?categor[ií]a|no\\s+es\\s+de\\s|era\\s+de\\s");

  static boolean isExplicitCorrection(String message) {
    return message != null && CORRECTION_PHRASES.matcher(message).find();
  }

  /** Señales de pregunta de confianza/seguridad sobre quién viene a la casa. */
  private static final List<String> TRUST_QUESTION_KEYWORDS = List.of(
      "de confianza", "confiable", "quien viene", "quién viene", "quien es el que viene",
      "es seguro", "son seguros", "verificado", "verificados", "antecedentes");

  static boolean isTrustQuestion(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    return TRUST_QUESTION_KEYWORDS.stream().anyMatch(normalized::contains);
  }

  private static final List<String> PRICE_QUESTION_KEYWORDS =
      List.of("cuanto", "cuánto", "precio", "sale", "cuesta", "vale");

  /** Detecta si el mensaje del cliente es una pregunta de precio (fallback sin LLM,
   * ver PLAN_SUPERAPP_CLIENTE.md Cotización Estimada punto 3). Heurística simple por
   * keywords, igual de espíritu que el resto de los clasificadores heurísticos del repo. */
  private boolean isPriceQuestion(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    return PRICE_QUESTION_KEYWORDS.stream().anyMatch(normalized::contains);
  }

  /**
   * Respuesta del fallback heurístico a una pregunta de precio: si hay categoría
   * definida y con rango cargado, responde el rango con el disclaimer de siempre.
   * Si hay categoría pero sin rango cargado, es honesto: el proveedor cotiza.
   * Si no hay categoría todavía, pide el dato antes de poder ayudar con precio.
   */
  private String heuristicPriceReply(Lead lead) {
    boolean hasCategory = lead.getDetectedCategory() != null && !lead.getDetectedCategory().isBlank()
        && !"otro".equalsIgnoreCase(lead.getDetectedCategory());
    if (!hasCategory) {
      return "Para darte una idea de precio primero necesito saber qué necesitás arreglar — ¿de qué se trata?";
    }
    String category = humanCategory(lead.getDetectedCategory());
    String range = com.fixy.backend.model.ServiceCategory.priceRangeLabelForId(lead.getDetectedCategory());
    if (range == null) {
      return "El precio de %s lo termina de confirmar el proveedor cuando vea el trabajo, así que no te quiero tirar un número inventado."
          .formatted(category);
    }
    // CTA post-precio (simulación 2026-08-06, persona "pregunta_precio": la
    // conversación moría después del rango — precio sin próximo paso es un
    // callejón sin salida).
    boolean zoneKnown = lead.getLocation() != null && !lead.getLocation().isBlank()
        && !"sin definir".equalsIgnoreCase(lead.getLocation());
    String cta = zoneKnown
        ? " ¿Querés que te busque uno en %s?".formatted(lead.getLocation())
        : " Si querés te consigo uno: ¿en qué zona estás?";
    return "Para %s el rango orientativo ronda %s (visita + trabajo simple), pero el precio final te lo confirma el proveedor cuando vea el trabajo.%s"
        .formatted(category, range, cta);
  }

  /** Una zona extraída por el LLM solo se acepta si aparece textualmente
   * (case-insensitive, sin acentos) en algún mensaje del CLIENTE de la
   * conversación reciente. Package-private para testear sin LLM real,
   * mismo patrón que buildContext/parseTurnJson. */
  boolean zoneMentionedByCustomer(Long leadId, String zone) {
    if (zone == null || zone.isBlank()) {
      return false;
    }
    String needle = stripAccents(zone.toLowerCase(Locale.ROOT)).trim();
    if (needle.isEmpty()) {
      return false;
    }
    // Matching por TOKENS distintivos, no por frase completa: el cliente
    // escribe "lomas" y el LLM canonicaliza a "Lomas de Solymar" (correcto) —
    // la versión anterior exigía la frase entera y rechazaba la zona real
    // (lead #123). Un token distintivo (>=4 letras, sin conectores) del
    // nombre canónico alcanza; "hola" sigue sin validar "Ciudad de la Costa".
    java.util.List<String> tokens = java.util.Arrays.stream(needle.split("\\s+"))
        .filter(t -> t.length() >= 4 && !ZONE_STOPWORDS.contains(t))
        .toList();
    if (tokens.isEmpty()) {
      return false;
    }
    return leadMessageService.recentForAgent(leadId, HISTORY_LIMIT).stream()
        .filter(m -> "customer".equals(m.getSender()) && m.getText() != null)
        .map(m -> stripAccents(m.getText().toLowerCase(Locale.ROOT)))
        .anyMatch(text -> tokens.stream().anyMatch(text::contains));
  }

  private static final java.util.Set<String> ZONE_STOPWORDS =
      java.util.Set.of("de", "del", "la", "las", "el", "los", "san", "santa");

  private static String stripAccents(String s) {
    return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
  }

  /** Una categoría extraída por el LLM solo se acepta si el CLIENTE dio algún
   * rastro de ella en sus propios mensajes: o bien
   * {@link com.fixy.backend.model.ServiceCategory#detectFromText} sobre el
   * texto del cliente devuelve esa misma categoría, o alguna de sus keywords
   * (ver {@link com.fixy.backend.model.ServiceCategory#keywords()}) aparece
   * ahí (sin acentos, case-insensitive). Mismo patrón que
   * zoneMentionedByCustomer — evita que el LLM le presuma una categoría a un
   * cliente que solo dijo "hola" (leads #116/#119 en prod). Package-private
   * para testear sin LLM real. */
  boolean categoryMentionedByCustomer(Long leadId, String category) {
    if (category == null || category.isBlank()) {
      return false;
    }
    java.util.Optional<com.fixy.backend.model.ServiceCategory> target =
        com.fixy.backend.model.ServiceCategory.fromId(category);
    if (target.isEmpty()) {
      // Categoría desconocida para el catálogo (no debería pasar dado el enum
      // del schema, pero si pasa no hay nada que validar contra keywords):
      // se rechaza, es más seguro que aceptar algo que no podemos verificar.
      return false;
    }
    String customerText = stripAccents(leadMessageService.recentForAgent(leadId, HISTORY_LIMIT).stream()
        .filter(m -> "customer".equals(m.getSender()) && m.getText() != null)
        .map(m -> m.getText().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(" ")));
    if (customerText.isBlank()) {
      return false;
    }
    // 1) Clasificador heurístico laxo sobre el texto del cliente: si coincide
    // con la misma categoría, es la validación más fuerte.
    java.util.Optional<com.fixy.backend.model.ServiceCategory> detected =
        com.fixy.backend.model.ServiceCategory.detectFromText(customerText);
    if (detected.isPresent() && detected.get() == target.get()) {
      return true;
    }
    // 2) Si detectFromText matcheó OTRA categoría primero (la búsqueda es
    // "primer match" en orden del enum), igual aceptamos si alguna keyword
    // propia de la categoría extraída aparece en el texto del cliente.
    for (String keyword : target.get().keywords()) {
      if (customerText.contains(stripAccents(keyword))) {
        return true;
      }
    }
    return false;
  }

  /**
   * true si la respuesta del LLM debe reemplazarse por la pregunta
   * determinista de zona: categoría ya conocida, zona todavía faltante (y no
   * llegó en este turno), el cliente NO está preguntando algo él mismo (ahí
   * el LLM debe poder responder libre), y la respuesta generada no pide la
   * zona. En ese estado, cualquier otra repregunta es una respuesta rota
   * (caso real lead #138). Estático y puro para testearlo sin contexto.
   */
  public static boolean shouldForceZoneQuestion(
      boolean categoryKnown,
      String location,
      String lastCustomerMsg,
      String reply,
      boolean zoneArrivedThisTurn
  ) {
    if (!categoryKnown || zoneArrivedThisTurn) {
      return false;
    }
    boolean zoneMissing = location == null || location.isBlank() || "sin definir".equalsIgnoreCase(location);
    if (!zoneMissing) {
      return false;
    }
    if (lastCustomerMsg != null && (lastCustomerMsg.contains("?") || lastCustomerMsg.contains("¿"))) {
      return false;
    }
    return !asksForZone(reply);
  }

  /** true si algún mensaje del CLIENTE de este lead trae la marca [smoke] (tráfico sintético). */
  /**
   * Señales explícitas de los mensajes del cliente (keywords de categoría y
   * zona) pisan lo extraído por el LLM — la base determinista de las
   * correcciones "me equivoqué". Usado por el camino LLM; opera sobre la
   * tanda de mensajes pendientes del turno con la misma semántica secuencial
   * que detectCategoryFromMessages.
   */
  private Map<String, String> withMessageSignals(List<String> messages, Map<String, String> extracted) {
    String cat = detectCategoryFromMessages(messages);
    String zone = null;
    String phone = null;
    for (String message : messages) {
      String z = agentService.areaMentionedIn(message);
      if (z != null) {
        zone = z; // la última mención gana, igual que en turnos secuenciales
      }
      if (phone == null) {
        phone = phoneMentionedIn(message);
      }
    }
    if (cat == null && zone == null && phone == null) {
      return extracted;
    }
    Map<String, String> merged = extracted == null
        ? new java.util.HashMap<>() : new java.util.HashMap<>(extracted);
    if (cat != null) {
      merged.put("category", cat);
    }
    if (zone != null) {
      merged.put("zone", zone);
    }
    if (phone != null && !merged.containsKey("phone")) {
      merged.put("phone", phone);
    }
    return merged;
  }

  /** Patrón de celular uruguayo: 09X + 7 dígitos, con o sin +598/espacios/guiones. */
  private static final java.util.regex.Pattern UY_PHONE = java.util.regex.Pattern.compile(
      "(?:(?:\\+?598)[\\s.-]?0?|0)(9\\d(?:[\\s.-]?\\d){6})(?!\\d)");

  /**
   * Teléfono detectado por REGEX en el texto del cliente (simulación
   * 2026-08-06, persona "happy_path": escribió 'mi teléfono es 099888111'
   * en el primer mensaje, el lead quedó sin teléfono, y encima el agente le
   * volvió a pedir el WhatsApp — doble vergüenza). Lo crítico va en código:
   * si el cliente YA dio el número, se captura pase lo que pase con el LLM.
   */
  public static String phoneMentionedIn(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    java.util.regex.Matcher m = UY_PHONE.matcher(message);
    if (!m.find()) {
      return null;
    }
    String digits = "0" + m.group(1).replaceAll("\\D", "");
    return digits.length() == 9 ? digits : null;
  }

  /**
   * "Hablar con una persona" (mejoras UX 2026-08, error #4 del mercado:
   * soporte inalcanzable). El cliente lo pide con un toque desde su pedido:
   * evento de timeline + Telegram a ops (idempotente por lead y con guard
   * de smoke, vía notifyEscalation) + confirmación honesta en el chat (una
   * sola vez — los toques repetidos no spamean).
   */
  public void requestHumanHelp(Long leadId) {
    Lead lead = leadRepository.findById(leadId).orElse(null);
    if (lead == null) {
      return;
    }
    leadTimelineService.appendEvent(lead, "HUMAN_HELP_REQUESTED", "customer",
        "El cliente pidió hablar con una persona");
    telegramNotifyService.notifyEscalation(lead, "el cliente pidió hablar con una persona",
        lastCustomerText(leadId));
    String confirmationMarker = "le avisé a una persona del equipo";
    boolean alreadyConfirmed = leadMessageService.recentForAgent(leadId, 15).stream()
        .anyMatch(m -> !"customer".equals(m.getSender())
            && m.getText() != null
            && m.getText().toLowerCase(Locale.ROOT).contains(confirmationMarker));
    if (!alreadyConfirmed) {
      leadMessageService.postFromAgent(leadId,
          "Listo, le avisé a una persona del equipo de Fixy 🙋 Te va a escribir por acá. "
              + "Mientras tanto podés seguir contándome lo que necesites.");
    }
  }

  /** Último mensaje del cliente (para desempates de clasificación); "" si no hay. */
  private String lastCustomerText(Long leadId) {
    try {
      return leadMessageService.recentForAgent(leadId, 10).stream()
          .filter(m -> "customer".equals(m.getSender()) && m.getText() != null)
          .reduce((a, b) -> b)
          .map(m -> m.getText())
          .orElse("");
    } catch (Exception ex) {
      return "";
    }
  }

  private boolean customerMentionedSmoke(Long leadId) {
    try {
      return leadMessageService.recentForAgent(leadId, 10).stream()
          .anyMatch(m -> "customer".equals(m.getSender())
              && com.fixy.backend.model.SmokeTraffic.marks(m.getText()));
    } catch (Exception ex) {
      return false;
    }
  }

  /**
   * Mejora diaria 2026-07-28 (dato: 73 de 87 leads reales de julio SIN
   * teléfono → cliente irrecuperable si cierra la pestaña). Pedido del
   * WhatsApp determinista, en código: UNA vez, recién cuando categoría y
   * zona ya están (no compite con las preguntas críticas del intake) y el
   * teléfono sigue vacío. Siempre opcional para el cliente ("seguimos por
   * acá") — la promesa del modelo es cero fricción, no un formulario.
   */
  static final String CONTACT_PHONE_ASK =
      "Por último: ¿me dejás un WhatsApp para avisarte apenas el proveedor confirme? "
          + "Si preferís, seguimos solo por acá.";

  public static boolean shouldAskContactPhone(
      boolean categoryKnown, boolean zoneKnown, String currentPhone,
      boolean phoneArrivedThisTurn, String reply) {
    if (!categoryKnown || !zoneKnown) {
      return false;
    }
    if (phoneArrivedThisTurn || (currentPhone != null && !currentPhone.isBlank())) {
      return false;
    }
    return !asksForContactPhone(reply);
  }

  /** true si la respuesta ya pide teléfono/WhatsApp (insensible a acentos). */
  public static boolean asksForContactPhone(String reply) {
    if (reply == null || reply.isBlank()) {
      return false;
    }
    String normalized = java.text.Normalizer.normalize(reply.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
    return normalized.contains("whatsapp")
        || normalized.contains("telefono")
        || (normalized.contains("numero") && normalized.contains("contact"));
  }

  /**
   * "El ticket del pedido" (caso real lead #200, 2026-08-04): el pedido de
   * un cliente anónimo vive solo en el navegador donde lo hizo — si lo
   * pierde y no dejó WhatsApp, es irrecuperable. Tras el matching, el agente
   * le muestra SU PROPIO link de recuperación (/c/{id}/{token}) para que lo
   * guarde — funciona en cualquier dispositivo, no depende del teléfono.
   * Una sola vez por pedido (las correcciones re-disparan el matching).
   */
  private void shareRecoveryLink(Lead lead) {
    try {
      if (lead.getAccessToken() == null || lead.getAccessToken().isBlank()) {
        return;
      }
      // Solo para quien NO tiene otro canal de vuelta: si ya hay teléfono
      // (dejó WhatsApp, o entró POR WhatsApp) el link es ruido — su canal
      // de recuperación es el número.
      if (lead.getPhone() != null && !lead.getPhone().isBlank()) {
        return;
      }
      String marker = "/c/" + lead.getId() + "/";
      boolean alreadyShared = leadMessageService.recentForAgent(lead.getId(), 30).stream()
          .anyMatch(m -> !"customer".equals(m.getSender())
              && m.getText() != null && m.getText().contains(marker));
      if (alreadyShared) {
        return;
      }
      leadMessageService.postFromAgent(lead.getId(),
          "📌 Guardá este link para volver a tu pedido cuando quieras, desde cualquier celular o computadora: %s/c/%d/%s"
              .formatted(publicAppBaseUrl, lead.getId(), lead.getAccessToken()));
    } catch (Exception ex) {
      log.warn("shareRecoveryLink failed for lead {}: {}", lead.getId(), ex.getMessage());
    }
  }

  /**
   * Nudge post-respuesta del proveedor (mismo caso #200): si el proveedor
   * escribió y el cliente sigue sin teléfono, una única insistencia — es el
   * momento en que el valor del canal de vuelta es obvio. Llamado desde el
   * posteo de mensajes del proveedor.
   */
  public void afterProviderMessage(Long leadId) {
    try {
      Lead lead = leadRepository.findById(leadId).orElse(null);
      if (lead == null || (lead.getPhone() != null && !lead.getPhone().isBlank())) {
        return;
      }
      String marker = "El proveedor te escribió";
      boolean alreadyNudged = leadMessageService.recentForAgent(leadId, 40).stream()
          .anyMatch(m -> !"customer".equals(m.getSender())
              && m.getText() != null && m.getText().contains(marker));
      if (alreadyNudged) {
        return;
      }
      leadMessageService.postFromAgent(leadId,
          "El proveedor te escribió 👆 Si me dejás un WhatsApp te aviso también por ahí cuando haya novedades — así no dependés de tener esta página abierta.");
    } catch (Exception ex) {
      log.warn("afterProviderMessage failed for lead {}: {}", leadId, ex.getMessage());
    }
  }

  /**
   * Anexa el pedido de WhatsApp a un mensaje del agente si corresponde
   * (sin teléfono en el lead y sin haberlo pedido antes). Para los mensajes
   * de matching, que ya implican categoría+zona resueltas.
   */
  private String withContactPhoneAsk(Lead lead, String message) {
    boolean phoneMissing = lead.getPhone() == null || lead.getPhone().isBlank();
    if (phoneMissing && !asksForContactPhone(message) && !contactPhoneAlreadyAsked(lead.getId())) {
      return message + " " + CONTACT_PHONE_ASK;
    }
    return message;
  }

  /** true si el agente ya pidió el WhatsApp en algún mensaje anterior — se pide UNA vez, no se insiste. */
  private boolean contactPhoneAlreadyAsked(Long leadId) {
    try {
      return leadMessageService.recentForAgent(leadId, 30).stream()
          .anyMatch(m -> !"customer".equals(m.getSender())
              && m.getText() != null
              && asksForContactPhone(m.getText()));
    } catch (Exception ex) {
      // Ante la duda no repreguntar: molesta más pedir dos veces que no pedir.
      return true;
    }
  }

  /** true si la respuesta menciona la zona/ubicación como pregunta o pedido (insensible a acentos). */
  public static boolean asksForZone(String reply) {
    if (reply == null || reply.isBlank()) {
      return false;
    }
    String normalized = java.text.Normalizer.normalize(reply.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
    return normalized.contains("zona")
        || normalized.contains("barrio")
        || normalized.contains("donde")
        || normalized.contains("ubicac")
        || normalized.contains("direccion");
  }

  /** true si la respuesta generada es (normalizada) igual al último mensaje
   * que el agente ya mandó — señal de LLM en loop. Package-private para test. */
  boolean isStuckRepeatingItself(Long leadId, String reply) {
    if (reply == null || reply.isBlank()) {
      return false;
    }
    List<LeadMessage> recent = leadMessageService.recentForAgent(leadId, HISTORY_LIMIT);
    // Contra los últimos 3 mensajes del agente, no solo el último: el 8B
    // también re-hace preguntas VIEJAS ya respondidas (lead #131: volvió a
    // "¿qué tamaño tiene el jardín?" dos preguntas después del "30 m").
    int checked = 0;
    for (int i = recent.size() - 1; i >= 0 && checked < 3; i--) {
      LeadMessage m = recent.get(i);
      if ("fixy".equals(m.getSender())) {
        checked++;
        if (tokenSimilarity(normalizeForComparison(m.getText()),
            normalizeForComparison(reply)) >= 0.8) {
          return true;
        }
      }
    }
    return false;
  }

  private static double tokenSimilarity(String a, String b) {
    java.util.Set<String> ta = new java.util.HashSet<>(java.util.Arrays.asList(a.split("\\s+")));
    java.util.Set<String> tb = new java.util.HashSet<>(java.util.Arrays.asList(b.split("\\s+")));
    ta.remove(""); tb.remove("");
    if (ta.isEmpty() || tb.isEmpty()) {
      return 0.0;
    }
    java.util.Set<String> inter = new java.util.HashSet<>(ta);
    inter.retainAll(tb);
    // Coeficiente de solapamiento (no Jaccard): el repetido típico del 8B es
    // un SUBCONJUNTO del mensaje anterior (misma pregunta, menos preámbulo) —
    // Jaccard lo diluye por la diferencia de largo; overlap lo clava en ~1.0.
    return (double) inter.size() / Math.min(ta.size(), tb.size());
  }

  private static String normalizeForComparison(String text) {
    if (text == null) {
      return "";
    }
    return stripAccents(text.toLowerCase(Locale.ROOT)).replaceAll("[^a-z0-9 ]", "").trim();
  }

  private void recordShortAnswerToLastQuestion(Long leadId, Lead lead) {
    try {
      List<LeadMessage> recent = leadMessageService.recentForAgent(leadId, HISTORY_LIMIT);
      if (recent.size() < 2) {
        return;
      }
      LeadMessage last = recent.get(recent.size() - 1);
      LeadMessage previous = recent.get(recent.size() - 2);
      if (!"customer".equals(last.getSender()) || !"fixy".equals(previous.getSender())) {
        return;
      }
      String answer = safe(last.getText(), "").trim();
      String question = safe(previous.getText(), "").trim();
      if (answer.isEmpty() || answer.length() > 80 || !question.contains("?")
          || isAcknowledgment(answer)) {
        return;
      }
      String entry = answer + " (respuesta a: " + (question.length() > 60
          ? question.substring(question.length() - 60) : question) + ")";
      String currentNotes = safe(lead.getNotes(), "");
      if (currentNotes.contains(answer)) {
        return; // ya registrado (por el LLM o por un turno anterior)
      }
      lead.setNotes(currentNotes.isBlank() ? entry : currentNotes + "\n" + entry);
      leadRepository.save(lead);
    } catch (Exception ex) {
      log.warn("recordShortAnswer failed lead {}: {}", leadId, ex.getMessage());
    }
  }

  private static final java.util.Set<String> ACKNOWLEDGMENTS = java.util.Set.of(
      "ok", "oka", "okey", "okay", "dale", "gracias", "muchas gracias", "perfecto",
      "listo", "genial", "buenisimo", "barbaro", "ta", "va", "de acuerdo", "entendido",
      "joya", "espero", "aguardo", "bueno", "bien");

  /** true si el mensaje es un cierre/asentimiento corto ("ok", "gracias"). */
  static boolean isAcknowledgment(String message) {
    if (message == null) {
      return false;
    }
    String normalized = stripAccents(message.toLowerCase(Locale.ROOT))
        .replaceAll("[^a-z ]", "").trim();
    return !normalized.isEmpty() && normalized.length() <= 20
        && ACKNOWLEDGMENTS.contains(normalized);
  }

  private String lastCustomerMessage(Long leadId) {
    List<LeadMessage> recent = leadMessageService.recentForAgent(leadId, HISTORY_LIMIT);
    for (int i = recent.size() - 1; i >= 0; i--) {
      LeadMessage m = recent.get(i);
      if ("customer".equals(m.getSender()) && m.getText() != null && !m.getText().isBlank()) {
        return m.getText();
      }
    }
    return null;
  }

  /**
   * Construye la respuesta del fallback determinista según lo que el lead
   * tiene confirmado tras la heurística. Reconoce categoría/zona si están, y
   * pide solo el dato que falta — nunca repregunta genérico si ya hay info.
   */
  private String heuristicFallbackReply(Lead lead) {
    boolean hasCategory = lead.getDetectedCategory() != null && !lead.getDetectedCategory().isBlank()
        && !"otro".equalsIgnoreCase(lead.getDetectedCategory());
    boolean hasZone = lead.getLocation() != null && !lead.getLocation().isBlank()
        && !"sin definir".equalsIgnoreCase(lead.getLocation());
    boolean hasUrgency = lead.getUrgency() != null && !lead.getUrgency().isBlank();

    if (!hasCategory && !hasZone) {
      // Mensaje vacío/ambiguo ("hola", "??"): no hay nada que reconocer, repregunta honesta.
      return "Contame un poco más: ¿qué te pasa o qué necesitás arreglar en tu casa?";
    }

    // Categoría que Fixy AÚN no cubre (simulación de clientes 2026-08-06,
    // persona "electricista": el guion pedía "dirección exacta para
    // coordinar" una coordinación imposible). Honestidad primero.
    if (hasCategory && !MVP_CATEGORIES.contains(lead.getDetectedCategory().toLowerCase().trim())) {
      return "Todavía no tenemos proveedores de %s en Fixy. Anoté tu pedido igual y te aviso por acá apenas sumemos uno — estamos creciendo."
          .formatted(humanCategory(lead.getDetectedCategory()));
    }

    // Pregunta de confianza ("¿quién viene? ¿es de confianza?") — simulación
    // 2026-08-06, persona "desconfiado": dos corridas seguidas cayeron al
    // guion genérico. La confianza es EL producto: respuesta digna en
    // código, pase lo que pase con el LLM.
    String lastMsg = lastCustomerMessage(lead.getId());
    if (isTrustQuestion(lastMsg)) {
      return "Todos los proveedores de Fixy están verificados por el equipo. Apenas se asigne el tuyo "
          + "vas a ver acá mismo su nombre, su calificación y los trabajos que ya hizo — y siempre decidís vos. "
          + "Cualquier problema, tocás \"Hablá con una persona\" y entra alguien del equipo.";
    }

    if (lead.isReadyForMatching()) {
      int providerCount = countProvidersInZone(lead.getDetectedCategory(), lead.getLocation());
      String category = humanCategory(lead.getDetectedCategory());
      if (providerCount > 0) {
        return "Anotado: problema de %s en %s. Ya tengo proveedores disponibles en tu zona, estoy buscando uno para vos."
            .formatted(category, lead.getLocation());
      }
      return "Anotado: problema de %s en %s. Por ahora no tenemos un proveedor de %s en tu zona, te avisamos por acá apenas haya."
          .formatted(category, lead.getLocation(), category);
    }

    StringBuilder ack = new StringBuilder("Anotado: ");
    if (hasCategory && hasZone) {
      ack.append("problema de ").append(humanCategory(lead.getDetectedCategory()))
          .append(" en ").append(lead.getLocation()).append(". ");
    } else if (hasCategory) {
      ack.append("problema de ").append(humanCategory(lead.getDetectedCategory())).append(". ");
    } else {
      ack.append("tu pedido en ").append(lead.getLocation()).append(". ");
    }

    if (!hasZone) {
      ack.append("¿En qué zona estás?");
    } else if (!hasUrgency) {
      ack.append("¿Es urgente o puede esperar unos días?");
    } else {
      ack.append("¿Me pasás la dirección exacta para coordinar?");
    }
    return ack.toString();
  }

  /** Acción opcional que el LLM puede pedir en el turno (Salto 2 del cerebro
   * agéntico, ver ARQUITECTURA_SUPERAPP.md). Hoy solo existe "escalate";
   * cualquier otro valor (ausente, "none", desconocido) se trata como
   * "none" — comportamiento idéntico al de antes de este cambio.
   * Package-private (no private): permite tests directos de parseTurnJson,
   * mismo patrón que buildContext ya usa para testear sin LLM real. */
  record AgentAction(String type, String reason, String summary) {
    static final AgentAction NONE = new AgentAction("none", null, null);
    boolean isEscalate() {
      return "escalate".equals(type);
    }
  }

  record AgentTurnResult(String reply, Map<String, String> extracted, AgentAction action) {}

  private AgentTurnResult respondAndExtractTurn(Lead lead, String context, String history) {
    // Catálogo derivado de ServiceCategory.MVP_IDS (fuente única) — antes era
    // una lista "plomeria|barometrica|..." fija a mano acá.
    String categoryOptions = String.join("|", com.fixy.backend.model.ServiceCategory.MVP_IDS) + "|otro|null";
    String userContent = context + "\n\nConversación reciente:\n" + history + """


        TAREA:
        1) Respondé al ÚLTIMO mensaje del cliente en español rioplatense (voseo: "necesitás",
           "dale" — JAMAS "vale" ni "necesitas"), breve y útil. NUNCA repitas la frase del
           cliente en primera persona: el que necesita es EL CLIENTE, no vos (mal: "Vale,
           necesito aire acondicionado"; bien: "Dale, aire acondicionado en Lomas.").
           Si el cliente recién pasó info (foto, dirección, detalles), agradecela y avanzá.
           No repitas lo que ya dijiste. Si todavía no sabés qué necesita, preguntá.
        2) Extraé datos estructurados del cliente que aparezcan en la conversación.
           REGLA DURA: extraé SOLO lo que el CLIENTE escribió en SUS mensajes. NUNCA extraigas
           una zona, categoría u otro dato que solo aparece en TUS preguntas o ejemplos
           (ej: si vos preguntaste "¿qué zona de Ciudad de la Costa?", eso NO es la zona del
           cliente). Si el cliente no lo dijo, va null.

        3) Decidí si hace falta escalar la conversación a una persona de Fixy (acción "escalate").
           Ver la sección "CUÁNDO ESCALAR" del prompt de sistema. Por defecto action.type es "none".

        FORMATO DE SALIDA: SOLO un JSON válido, sin texto antes ni después, con esta estructura:
        {
          "reply": "tu respuesta conversacional al cliente",
          "extracted": {
            "category": "%s",
            "zone": "Solymar|Lagomar|El Pinar|Shangrilá|Barra de Carrasco|Parque Miramar|San José de Carrasco|Lomas de Solymar|Montes de Solymar|Colinas de Solymar|Aeroparque|Ciudad de la Costa|otro|null",
            "urgency": "alta|media|baja|null",
            "phone": "099XXXXXX o null",
            "name": "nombre o null",
            "address": "dirección exacta o null",
            "details": "detalles relevantes o null"
          },
          "action": {
            "type": "none|escalate",
            "reason": "motivo corto del escalamiento, o null si type es none",
            "summary": "resumen de 1 línea de la situación para la persona que va a atender, o null si type es none"
          }
        }

        Reglas para extracted:
        - Usá null cuando el dato no aparezca en la conversación (no inventes).
        - Sólo extraé valores que el cliente dijo explícitamente o son obvios del contexto.
        - phone debe tener formato uruguayo: 8-9 dígitos empezando con 09 ó 9.
        - Si category es "pasteleria", incluí en "details" lo que el cliente haya dicho sobre
          fecha del evento, cantidad de personas o porciones, y temática/tipo de torta.

        Reglas para action:
        - Default: {"type": "none", "reason": null, "summary": null}. Usalo salvo que aplique escalar.
        - "reply" SIEMPRE debe ser la respuesta honesta al cliente, sea cual sea action.type — si
          escalás, "reply" debe avisarle al cliente que lo vas a poner en contacto con una persona.
        """.formatted(categoryOptions);
    String raw;
    if ("workersai".equals(provider)) {
      raw = callWorkersAiJson(SYSTEM_PROMPT, userContent);
    } else if ("ollama".equals(provider)) {
      raw = callOllama(SYSTEM_PROMPT + "\n\n" + userContent);
    } else {
      raw = callOpenAi(SYSTEM_PROMPT + "\n\n" + userContent);
    }
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return parseTurnJson(raw);
  }

  /** Llama Workers AI pidiendo JSON puro como response. */
  private String callWorkersAiJson(String systemContent, String userContent) {
    if (cloudflareAccountId == null || cloudflareAccountId.isBlank()
        || cloudflareApiToken == null || cloudflareApiToken.isBlank()) {
      return null;
    }
    try {
      // Fuente única: com.fixy.backend.model.ServiceCategory.MVP_IDS. El turno
      // conversacional solo pide categorías MVP (matching real) + "otro".
      List<String> categoryEnum = java.util.stream.Stream.concat(
          com.fixy.backend.model.ServiceCategory.MVP_IDS.stream(), java.util.stream.Stream.of("otro")).toList();
      List<String> zoneEnum = List.of("Solymar", "Lagomar", "El Pinar", "Shangrilá", "Barra de Carrasco",
          "Parque Miramar", "San José de Carrasco", "Lomas de Solymar", "Montes de Solymar", "Colinas de Solymar",
          "Aeroparque", "Ciudad de la Costa", "otro");
      List<String> urgencyEnum = List.of("alta", "media", "baja");
      Map<String, Object> turnSchema = Map.of(
          "type", "object",
          "properties", Map.of(
              "reply", Map.of("type", "string"),
              "extracted", Map.of(
                  "type", "object",
                  "properties", Map.of(
                      "category", Map.of("type", "string", "enum", categoryEnum),
                      "zone", Map.of("type", "string", "enum", zoneEnum),
                      "urgency", Map.of("type", "string", "enum", urgencyEnum),
                      "phone", Map.of("type", "string"),
                      "name", Map.of("type", "string"),
                      "address", Map.of("type", "string"),
                      "details", Map.of("type", "string")
                  )
              ),
              "action", Map.of(
                  "type", "object",
                  "properties", Map.of(
                      "type", Map.of("type", "string", "enum", List.of("none", "escalate")),
                      "reason", Map.of("type", "string"),
                      "summary", Map.of("type", "string")
                  )
              )
          ),
          "required", List.of("reply", "extracted")
      );
      Map<String, Object> payload = Map.of(
          "messages", List.of(
              Map.of("role", "system", "content", systemContent),
              Map.of("role", "user", "content", userContent)
          ),
          "max_tokens", 350,
          "temperature", 0.3,
          "response_format", Map.of("type", "json_schema", "json_schema", turnSchema)
      );
      String uri = "/accounts/" + cloudflareAccountId + "/ai/run/" + cloudflareModel;
      String raw = cloudflareClient.post()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + cloudflareApiToken)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(15))
          .retry(1)
          .block();
      if (raw == null || raw.isBlank()) {
        return null;
      }
      JsonNode root = objectMapper.readTree(raw);
      if (!root.path("success").asBoolean(false)) {
        log.warn("workersai-json non-success: {}", raw.length() > 300 ? raw.substring(0, 300) : raw);
        return null;
      }
      JsonNode response = root.path("result").path("response");
      if (response.isTextual()) {
        return response.asText();
      }
      // Algunos modelos devuelven el objeto JSON directo en response.
      if (response.isObject()) {
        return response.toString();
      }
      return null;
    } catch (Exception ex) {
      log.warn("workersai-json call failed: {}", ex.getMessage());
      return null;
    }
  }

  /** Package-private: testeado directo (sin LLM real), mismo patrón que buildContext. */
  AgentTurnResult parseTurnJson(String raw) {
    try {
      String trimmed = raw.trim();
      // Algunos modelos meten ```json ``` o texto extra. Buscar el primer { y último }.
      int first = trimmed.indexOf('{');
      int last = trimmed.lastIndexOf('}');
      if (first < 0 || last <= first) {
        log.warn("turn-json: no JSON object found in response (first={}, last={})", first, last);
        return new AgentTurnResult(raw.trim(), java.util.Map.of(), AgentAction.NONE);
      }
      String jsonOnly = trimmed.substring(first, last + 1);
      JsonNode root = objectMapper.readTree(jsonOnly);
      String reply = root.path("reply").asText("").trim();
      JsonNode ext = root.path("extracted");
      java.util.Map<String, String> extracted = new java.util.HashMap<>();
      if (ext.isObject()) {
        for (String key : List.of("category", "zone", "urgency", "phone", "name", "address", "details")) {
          JsonNode v = ext.path(key);
          if (v.isTextual()) {
            String s = v.asText().trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
              extracted.put(key, s);
            }
          }
        }
      }
      AgentAction action = parseAction(root.path("action"));
      if (reply.isEmpty()) {
        return new AgentTurnResult(null, extracted, action);
      }
      return new AgentTurnResult(reply, extracted, action);
    } catch (Exception ex) {
      log.warn("turn-json parse failed: {}", ex.getMessage());
      // Fallback: tratar todo el raw como reply text (sin extracción).
      return new AgentTurnResult(raw.trim(), java.util.Map.of(), AgentAction.NONE);
    }
  }

  /**
   * Parsea el campo opcional "action" del JSON de turno. Ausente, no-objeto,
   * "none", o cualquier tipo desconocido => AgentAction.NONE (comportamiento
   * idéntico al de antes de este campo existir). Nunca lanza.
   */
  private AgentAction parseAction(JsonNode actionNode) {
    if (actionNode == null || !actionNode.isObject()) {
      return AgentAction.NONE;
    }
    String type = actionNode.path("type").asText("none").trim().toLowerCase(java.util.Locale.ROOT);
    if (!"escalate".equals(type)) {
      return AgentAction.NONE;
    }
    String reason = actionNode.path("reason").asText("").trim();
    String summary = actionNode.path("summary").asText("").trim();
    return new AgentAction("escalate", reason.isEmpty() ? "no especificado" : reason, summary);
  }

  /**
   * Aplica campos extraídos al Lead. Solo escribe sobre campos vacíos
   * para no sobrescribir info confirmada en turnos anteriores.
   */
  private void applyExtractedFields(Long leadId, Map<String, String> extracted) {
    applyExtractedFieldsAndReport(leadId, extracted);
  }

  /**
   * Igual que {@link #applyExtractedFields}, pero informa si el lead justo
   * cruzó a readyForMatching en esta llamada (y por lo tanto tryAutoMatch ya
   * posteó un mensaje al cliente sobre el resultado del matching). Los
   * llamadores que van a postear su propio mensaje de reconocimiento usan
   * este dato para no duplicar el aviso de matching.
   */
  private boolean applyExtractedFieldsAndReport(Long leadId, Map<String, String> extracted) {
    try {
      Lead lead = leadRepository.findById(leadId).orElse(null);
      if (lead == null) return false;
      boolean changed = false;
      String cat = extracted.get("category");
      // Fallback heurístico: si el LLM devolvió "otro" o null, intentar detectar
      // del último mensaje del cliente. Asi capturamos categorias obvias que el LLM duda.
      if (cat == null || cat.equalsIgnoreCase("otro")) {
        String heuristic = heuristicCategory(lead);
        if (heuristic != null) cat = heuristic;
      }
      // Desempate determinista pastelería/decoración sobre lo que dijo el
      // modelo (banco: mixto_cumple_decoracion; ver ServiceCategory).
      if (cat != null) {
        cat = com.fixy.backend.model.ServiceCategory.refineCategoryId(lastCustomerText(leadId), cat);
      }
      // "Me equivoqué, no es X" (pedido de Carlos 2026-07-30, pensando en
      // personas mayores): mientras el pedido NO tenga proveedor contactado,
      // la corrección natural del cliente en el chat actualiza categoría y
      // zona — antes quedaban clavadas al primer valor y corregir charlando
      // no hacía nada. Después del matching no se pisa en silencio: ahí ya
      // hay un proveedor notificado y el cambio lo maneja ops.
      boolean preMatching = lead.getAssignedProviderId() == null
          && lead.getStatus() == com.fixy.backend.model.LeadStatus.NEW;
      boolean categoryBlank = lead.getDetectedCategory() == null
          || lead.getDetectedCategory().isBlank()
          || "otro".equalsIgnoreCase(lead.getDetectedCategory());
      boolean corrected = false;
      // Cambiar una categoría YA puesta exige intención explícita de
      // corrección en el mensaje (prueba de Carlos lead #235: "quiero agua
      // en el Tata" en un pedido de mandados lo pasaba a plomería — la
      // lista del mandado siempre nombra productos que son keywords de
      // otras categorías). Ver CORRECTION_PHRASES.
      boolean correctionIntent = isExplicitCorrection(lastCustomerText(leadId));
      if (cat != null && !cat.equalsIgnoreCase("otro")
          && (categoryBlank
              || (preMatching && correctionIntent && !cat.equalsIgnoreCase(lead.getDetectedCategory())))) {
        corrected = corrected || !categoryBlank;
        lead.setDetectedCategory(cat.toLowerCase().trim());
        changed = true;
      }
      String zone = extracted.get("zone");
      boolean zoneBlank = lead.getLocation() == null || lead.getLocation().isBlank();
      if (zone != null && !zone.equalsIgnoreCase("otro")
          && (zoneBlank || (preMatching && !zone.trim().equalsIgnoreCase(lead.getLocation())))) {
        corrected = corrected || !zoneBlank;
        lead.setLocation(zone.trim());
        changed = true;
      }
      String urgency = extracted.get("urgency");
      if (urgency != null && (lead.getUrgency() == null || lead.getUrgency().isBlank())) {
        lead.setUrgency(urgency.toLowerCase().trim());
        changed = true;
      }
      String phone = extracted.get("phone");
      if (phone != null && (lead.getPhone() == null || lead.getPhone().isBlank())) {
        lead.setPhone(phone.trim());
        changed = true;
      }
      String name = extracted.get("name");
      if (name != null && (lead.getName() == null || lead.getName().isBlank())) {
        lead.setName(name.trim());
        changed = true;
      }
      String address = extracted.get("address");
      String details = extracted.get("details");
      String currentNotes = lead.getNotes() == null ? "" : lead.getNotes();
      if (address != null && !currentNotes.contains(address)) {
        currentNotes = currentNotes.isBlank() ? ("Dirección: " + address) : (currentNotes + "\nDirección: " + address);
        lead.setNotes(currentNotes);
        changed = true;
      }
      if (details != null && !currentNotes.contains(details)) {
        currentNotes = currentNotes.isBlank() ? details : (currentNotes + "\n" + details);
        lead.setNotes(currentNotes);
        changed = true;
      }
      if (lead.getProblem() == null || "(pendiente)".equals(lead.getProblem())) {
        String composed = composeProblemFromExtracted(extracted);
        if (composed != null) {
          // Preservar la marca [smoke] del mensaje original del cliente: TODOS
          // los guards anti-tráfico-sintético (schedulers, Telegram, cobranzas)
          // la buscan en problem — y el problem derivado ("Pedido de X") la
          // perdía, dejando a los leads de prueba de chat SIN protección.
          // Incidente real 2026-07-24: el banco de evaluación de modelos
          // disparó pushes y avisos de matching estancado a proveedores reales.
          if (customerMentionedSmoke(lead.getId())) {
            composed = "[smoke] " + composed;
          }
          lead.setProblem(composed);
          changed = true;
        }
      }
      if (changed) {
        boolean wasReady = lead.isReadyForMatching();
        // Recomputar readyForMatching con la lógica del LeadService.
        boolean nowReady = hasMatchingRequirements(lead);
        lead.setReadyForMatching(nowReady);
        leadRepository.save(lead);
        // Si justo cruzó a "ready" en este turno, intentar matching automático.
        // También si el cliente CORRIGIÓ categoría/zona con el pedido ya
        // completo (2026-07-30): el automatch anterior corrió con los datos
        // viejos — sin re-disparo, el pedido corregido quedaba NEW para
        // siempre aunque hubiera proveedor para la categoría nueva (visto en
        // la verificación en prod: pastelería→decoración nunca contactó a
        // la proveedora de decoración).
        if (nowReady && (!wasReady || corrected)) {
          tryAutoMatch(lead);
          return true;
        }
      }
      return false;
    } catch (Exception ex) {
      log.warn("applyExtractedFields failed for lead {}: {}", leadId, ex.getMessage());
      return false;
    }
  }

  private boolean hasMatchingRequirements(Lead lead) {
    String cat = lead.getDetectedCategory() == null ? "" : lead.getDetectedCategory().toLowerCase().trim();
    String loc = lead.getLocation() == null ? "" : lead.getLocation().toLowerCase().trim();
    if (cat.isBlank() || "otro".equals(cat) || !MVP_CATEGORIES.contains(cat)) return false;
    if (loc.isBlank() || "sin definir".equals(loc)
        || !com.fixy.backend.model.CoverageZone.isCovered(loc)) return false;
    return true;
  }

  /**
   * Cuando el lead recién quedó matching-ready, busca proveedores y, si hay,
   * postea un mensaje de Fixy diciendo a qué proveedor está contactando.
   * Por ahora no envía WhatsApp automático — ese paso lo hace el ops humano
   * con el link wa.me que va en la timeline.
   */
  private void tryAutoMatch(Lead lead) {
    try {
      List<ProviderCatalogItem> matches = providerCatalogService.findMatchesForLead(
          lead.getId(), lead.getDetectedCategory(), lead.getLocation());
      if (matches == null || matches.isEmpty()) {
        // "Te aviso por acá" sin teléfono es una promesa vacía si el cliente
        // cierra la pestaña: este mensaje es EL lugar donde pedir el WhatsApp
        // (verificación post-deploy 2026-07-28: el flujo de pedido completo
        // saltea la respuesta conversacional y entra directo acá).
        leadMessageService.postFromAgent(lead.getId(), withContactPhoneAsk(lead,
            "Por ahora no tengo proveedores libres en %s para %s. Te aviso por acá apenas alguien levante el pedido."
                .formatted(lead.getLocation(), humanCategory(lead.getDetectedCategory()))));
        shareRecoveryLink(lead);
        safeTelegramNotifyDemandWithoutSupply(lead);
        return;
      }
      contactTopMatch(lead, matches, false);
    } catch (Exception ex) {
      log.warn("tryAutoMatch failed for lead {}: {}", lead.getId(), ex.getMessage());
    }
  }

  /**
   * Segunda oportunidad de matching para un lead que quedó HUÉRFANO: estaba
   * listo, en ese momento no había proveedor para su categoría/zona y el
   * sistema le prometió al cliente "te aviso apenas alguien levante el
   * pedido". Nadie volvía a intentarlo nunca — {@link #tryAutoMatch} corre
   * una sola vez, en el instante en que el lead cruza a readyForMatching
   * ({@code !wasReady && nowReady}), así que un proveedor que se registra
   * DESPUÉS jamás ve la demanda que ya estaba esperando.
   *
   * Dato que lo motivó (embudo de prod, 2026-07-29): 5 pedidos reales
   * esperaban con proveedor ACTIVO ya registrado en su misma categoría y
   * zona — 3 de aires (#128/#135/#147, Carnot Clima se registró el 23/07) y
   * 2 de decoración (#150/#180, Daya Dream Deco se registró el 25/07). Sus
   * timelines tenían UN solo evento: el de creación.
   *
   * Devuelve true si consiguió proveedor y lo contactó. La elegibilidad del
   * lead la decide {@link OrphanMatchRetryScheduler}; acá solo se reintenta.
   */
  public boolean retryAutoMatch(Long leadId) {
    try {
      Lead lead = leadRepository.findById(leadId).orElse(null);
      if (lead == null) {
        return false;
      }
      // Para lead concreto: excluye a los que ya rechazaron ESTE pedido. Sin
      // este filtro el reintento reofrecía el mismo lead al mismo proveedor
      // en cada ciclo (bug real del 2026-07-29, leads #128/#135/#147).
      List<ProviderCatalogItem> matches = providerCatalogService.findMatchesForLead(
          leadId, lead.getDetectedCategory(), lead.getLocation());
      if (matches == null || matches.isEmpty()) {
        // Silencio a propósito: el cliente YA recibió el aviso honesto de
        // "por ahora no tengo proveedores libres" cuando el pedido quedó
        // listo. Repetirlo en cada ciclo del scheduler sería spam.
        return false;
      }
      contactTopMatch(lead, matches, true);
      return true;
    } catch (Exception ex) {
      log.warn("retryAutoMatch failed for lead {}: {}", leadId, ex.getMessage());
      return false;
    }
  }

  /**
   * Contacta al mejor proveedor de la lista: push, asignación, timeline,
   * aviso al cliente y template de WhatsApp. Compartido por el matching del
   * momento ({@link #tryAutoMatch}) y por el reintento diferido
   * ({@link #retryAutoMatch}) — {@code retry} solo cambia el texto, para que
   * el cliente entienda que esto es la promesa cumplida y no un mensaje
   * suelto meses después.
   */
  private void contactTopMatch(Lead lead, List<ProviderCatalogItem> matches, boolean retry) {
    safeTelegramNotifyOpportunity(lead, matches);
    ProviderCatalogItem top = matches.get(0);
    com.fixy.backend.model.Provider providerEntity = providerRepository.findById(top.id()).orElse(null);

    // Push al proveedor matcheado (si se suscribió): el camino AUTOMÁTICO
    // también avisa, no solo el manual de generateMatches. Async y no-op
    // sin claves VAPID — nunca interrumpe el matching.
    if (providerEntity != null && !isSmokeLead(lead)) {
      pushNotificationService.notifyProvider(
          providerEntity.getId(),
          providerEntity.getAccessToken(),
          "Nueva oportunidad para vos",
          "%s en %s — entrá a tu panel para aceptarla".formatted(
              humanCategory(lead.getDetectedCategory()),
              safe(lead.getLocation(), "tu zona")));
    }

    // Marco el lead como "esperando respuesta del proveedor" para que el
    // webhook pueda vincular las respuestas de WhatsApp al lead correcto.
    lead.setAssignedProviderId(top.id());
    lead.setAssignedProvider(top.name());
    lead.setStatus(com.fixy.backend.model.LeadStatus.PROVIDER_CONTACTED);
    leadRepository.save(lead);
    leadTimelineService.appendEvent(lead, "PROVIDER_CONTACTED", "system",
        retry
            ? "Reintento de matching: contactando a %s (el pedido esperaba sin proveedor)".formatted(top.name())
            : "Contactando a %s via WhatsApp".formatted(top.name()));

    // Aviso conversacional al cliente: contactando, NO "conseguido" — todavía
    // no hay confirmación real del proveedor (ver PLAN_SUPERAPP_CLIENTE.md
    // Ola 1 #2). Si el proveedor rechaza después, el cliente no debe sentir
    // que le mintieron.
    leadMessageService.postFromAgent(lead.getId(), withContactPhoneAsk(lead,
        retry
            ? "¡Buenas noticias! Apareció un proveedor para tu pedido: estoy contactando a %s para %s en %s. Te aviso por acá apenas confirme."
                .formatted(top.name(), humanCategory(lead.getDetectedCategory()), lead.getLocation())
            : "Estoy contactando a %s para %s en %s. Te aviso por acá apenas confirme."
                .formatted(top.name(), humanCategory(lead.getDetectedCategory()), lead.getLocation())));
    shareRecoveryLink(lead);

    // Envio del template a WhatsApp del proveedor. Si fixy.whatsapp.* no
    // está configurado, WhatsAppService.sendTemplate retorna false y
    // queda solo el aviso al cliente (legacy manual con wa.me).
    if (providerEntity != null && whatsappService.isEnabled()) {
      String to = providerEntity.getWhatsappNumber();
      if (to == null || to.isBlank()) to = providerEntity.getPhone();
      if (to != null && !to.isBlank()) {
        boolean sent = whatsappService.sendTemplate(
            to,
            whatsappTemplateName,
            whatsappTemplateLang,
            List.of(
                humanCategory(lead.getDetectedCategory()),
                lead.getLocation() == null ? "" : lead.getLocation(),
                lead.getUrgency() == null ? "media" : lead.getUrgency()
            )
        );
        if (!sent) {
          log.warn("autoMatch: WhatsApp template send failed para lead {} provider {}", lead.getId(), top.id());
        }
      }
    }
  }

  /** Evento propio del dispatcher: gatea el mensaje al cliente ("te paso con
   * una persona") para que no se repita en cada turno mientras el lead sigue
   * escalado. Distinto del evento ESCALATED_TO_HUMAN que escribe
   * TelegramNotifyService — esa es la idempotencia propia del aviso a
   * Carlos (mismo patrón self-contenido que OPS_NOTIFIED_OPPORTUNITY), esta
   * es la idempotencia del lado cliente. Dos guards independientes es
   * intencional: si Telegram está deshabilitado (sin credenciales, como en
   * dev), el cliente igual no debe recibir el mensaje de escalamiento dos
   * veces. */
  private static final String CUSTOMER_NOTIFIED_ESCALATION_EVENT_TYPE = "CUSTOMER_NOTIFIED_ESCALATION";

  /**
   * Salto 2 del cerebro agéntico (tool-calling explícito, ver
   * ARQUITECTURA_SUPERAPP.md): el LLM pidió "escalate" en el turno. Avisa a
   * Carlos por Telegram (reusa TelegramNotifyService, que ya es idempotente y
   * respeta el guard "[smoke]" para el aviso en sí) y deja un mensaje honesto
   * al cliente en el chat del lead. Idempotente a nivel de este método
   * también (ver CUSTOMER_NOTIFIED_ESCALATION_EVENT_TYPE) y respeta el mismo
   * guard "[smoke]" que Telegram para no escalar leads de prueba.
   * Package-private: testeado directo, mismo patrón que buildContext.
   */
  void dispatchEscalation(Long leadId, AgentAction action) {
    try {
      Lead lead = leadRepository.findById(leadId).orElse(null);
      if (lead == null) return;
      if (isSmokeLead(lead)) return;
      if (leadTimelineService.hasEvent(leadId, CUSTOMER_NOTIFIED_ESCALATION_EVENT_TYPE)) {
        return;
      }
      leadMessageService.postFromAgent(leadId,
          "Te paso con una persona de Fixy para resolver esto mejor, en breve te contactan.");
      leadTimelineService.appendEvent(lead, CUSTOMER_NOTIFIED_ESCALATION_EVENT_TYPE, "system",
          "Escalado a humano: " + safe(action.reason(), "no especificado"));
      telegramNotifyService.notifyEscalation(lead, action.reason(), action.summary());
    } catch (Exception ex) {
      log.warn("dispatchEscalation failed for lead {}: {}", leadId, ex.getMessage());
    }
  }

  private boolean isSmokeLead(Lead lead) {
    String problem = lead.getProblem();
    return com.fixy.backend.model.SmokeTraffic.marks(problem);
  }

  /** Nunca debe interrumpir tryAutoMatch: TelegramNotifyService ya se protege
   *  internamente, pero esto es una segunda red de seguridad barata. */
  private void safeTelegramNotifyOpportunity(Lead lead, List<ProviderCatalogItem> matches) {
    try {
      telegramNotifyService.notifyOpportunityWithMatches(lead, matches);
    } catch (Exception ex) {
      log.warn("telegram notifyOpportunity failed for lead {}: {}", lead.getId(), ex.getMessage());
    }
  }

  private void safeTelegramNotifyDemandWithoutSupply(Lead lead) {
    try {
      telegramNotifyService.notifyDemandWithoutSupply(lead);
    } catch (Exception ex) {
      log.warn("telegram notifyDemandWithoutSupply failed for lead {}: {}", lead.getId(), ex.getMessage());
    }
  }

  /**
   * Detecta categoría buscando keywords en el último mensaje del cliente y/o
   * en el problema del lead. Fallback cuando el LLM duda y devuelve "otro".
   * Deriva del catálogo único ServiceCategory (ver su javadoc) — antes esta
   * lista de keywords estaba duplicada a mano acá y en AgentService.detectService.
   */
  private String heuristicCategory(Lead lead) {
    StringBuilder text = new StringBuilder();
    if (lead.getProblem() != null) text.append(lead.getProblem().toLowerCase()).append(' ');
    try {
      List<LeadMessage> msgs = leadMessageService.recentForAgent(lead.getId(), 4);
      for (LeadMessage m : msgs) {
        if ("customer".equals(m.getSender()) && m.getText() != null) {
          text.append(m.getText().toLowerCase()).append(' ');
        }
      }
    } catch (Exception ignored) {}
    return com.fixy.backend.model.ServiceCategory.detectFromText(text.toString())
        .map(com.fixy.backend.model.ServiceCategory::id)
        .orElse(null);
  }

  private String composeProblemFromExtracted(Map<String, String> extracted) {
    String cat = extracted.get("category");
    String details = extracted.get("details");
    if (details != null && !details.isBlank()) {
      return details;
    }
    if (cat != null && !cat.isBlank() && !cat.equalsIgnoreCase("otro")) {
      return "Pedido de " + humanCategory(cat);
    }
    return null;
  }

  private String callLlm(String context, String instruction) {
    String userContent = context + "\n\n" + instruction;
    String legacyPrompt = SYSTEM_PROMPT + "\n\n" + userContent;
    return switch (provider) {
      case "ollama" -> callOllama(legacyPrompt);
      case "workersai" -> callWorkersAi(SYSTEM_PROMPT, userContent);
      default -> callOpenAi(legacyPrompt);
    };
  }

  private String callOpenAi(String prompt) {
    if (openAiApiKey == null || openAiApiKey.isBlank()) {
      return null;
    }
    try {
      Map<String, Object> payload = AgentService.buildResponsesPayload(openAiModel, prompt);
      String raw = openAiClient.post()
          .uri("/responses")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          // 40s + retry: paridad con el path de Cloudflare; el timeout de
          // 20s cortaba la primera llamada post-boot (2026-07-27).
          .timeout(Duration.ofSeconds(40))
          .retry(1)
          .block();
      if (raw == null || raw.isBlank()) {
        return null;
      }
      JsonNode root = objectMapper.readTree(raw);
      JsonNode outputText = root.path("output_text");
      if (outputText.isTextual() && !outputText.asText().isBlank()) {
        return outputText.asText().trim();
      }
      JsonNode output = root.path("output");
      if (output.isArray() && output.size() > 0) {
        JsonNode first = output.get(0);
        if (first.has("content") && first.get("content").isArray()) {
          for (JsonNode item : first.get("content")) {
            if (item.has("text")) {
              return item.get("text").asText().trim();
            }
          }
        }
      }
      return null;
    } catch (Exception ex) {
      log.warn("openai call failed: {}", ex.getMessage());
      return null;
    }
  }

  private String callWorkersAi(String systemContent, String userContent) {
    if (cloudflareAccountId == null || cloudflareAccountId.isBlank()
        || cloudflareApiToken == null || cloudflareApiToken.isBlank()) {
      log.warn("workersai: missing CF_ACCOUNT_ID or CF_API_TOKEN");
      return null;
    }
    try {
      Map<String, Object> payload = Map.of(
          "messages", List.of(
              Map.of("role", "system", "content", systemContent),
              Map.of("role", "user", "content", userContent)
          ),
          "max_tokens", 200,
          "temperature", 0.4
      );
      String uri = "/accounts/" + cloudflareAccountId + "/ai/run/" + cloudflareModel;
      String raw = cloudflareClient.post()
          .uri(uri)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + cloudflareApiToken)
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(15))
          .retry(1)
          .block();
      if (raw == null || raw.isBlank()) {
        return null;
      }
      JsonNode root = objectMapper.readTree(raw);
      if (!root.path("success").asBoolean(false)) {
        log.warn("workersai non-success: {}", raw.length() > 300 ? raw.substring(0, 300) : raw);
        return null;
      }
      JsonNode response = root.path("result").path("response");
      if (response.isTextual() && !response.asText().isBlank()) {
        return response.asText().trim();
      }
      return null;
    } catch (Exception ex) {
      log.warn("workersai call failed: {}", ex.getMessage());
      return null;
    }
  }

  private String callOllama(String prompt) {
    try {
      Map<String, Object> payload = Map.of(
          "model", ollamaModel,
          "prompt", prompt,
          "stream", false,
          "options", Map.of("temperature", 0.3, "num_predict", 150, "top_p", 0.9)
      );
      String raw = ollamaClient.post()
          .uri("/api/generate")
          .bodyValue(payload)
          .retrieve()
          .bodyToMono(String.class)
          .timeout(Duration.ofSeconds(120))
          .block();
      if (raw == null || raw.isBlank()) {
        return null;
      }
      JsonNode root = objectMapper.readTree(raw);
      JsonNode response = root.path("response");
      if (response.isTextual() && !response.asText().isBlank()) {
        return response.asText().trim();
      }
      return null;
    } catch (Exception ex) {
      log.warn("ollama call failed: {}", ex.getMessage());
      return null;
    }
  }

  String buildContext(Lead lead) {
    return buildContext(lead, null);
  }

  /**
   * @param provisionalCategoryId categoría detectada determinísticamente del
   *   ÚLTIMO mensaje del cliente cuando el lead todavía no tiene una — el
   *   contexto se arma ANTES de la extracción del turno, así que sin esto el
   *   primer turno sale sin guion de intake y el 8B improvisa preguntas de
   *   otro rubro ("¿porciones?" para un aire — visto 3 veces en prod).
   */
  String buildContext(Lead lead, String provisionalCategoryId) {
    // OJO: el catálogo de proveedores matchea por el ID de categoría
    // ("pasteleria"), no por la etiqueta humana ("Pastelería") — pasarle la
    // etiqueta hacía contar 0 y el agente le decía al cliente "no hay
    // proveedores" mientras el auto-match SÍ encontraba uno (lead #108/#109).
    String rawCategory = safe(lead.getDetectedCategory(), "");
    if (rawCategory.isBlank() && provisionalCategoryId != null && !provisionalCategoryId.isBlank()) {
      rawCategory = provisionalCategoryId;
    }
    String rawLocation = safe(lead.getLocation(), "");
    String category = humanCategory(rawCategory.isBlank() ? "sin definir" : rawCategory);
    String location = safe(lead.getLocation(), "sin definir");
    String urgency = safe(lead.getUrgency(), "no especificada");
    boolean categoryKnown = !rawCategory.isBlank() && !"otro".equalsIgnoreCase(rawCategory);
    boolean locationKnown = !rawLocation.isBlank() && !"sin definir".equalsIgnoreCase(rawLocation);
    int providerCount = (categoryKnown && locationKnown)
        ? countProvidersInZone(rawCategory, rawLocation)
        : 0;
    String missing = safe(lead.getMissingFields(), "").replace("||", ", ");
    if (missing.isBlank()) missing = "ninguno";

    String coverageHint = "";
    String action = safe(deriveNextAction(lead), "");
    if ("out_of_coverage_area".equals(action)) {
      coverageHint = "\nINSTRUCCION DURA: la zona '" + location + "' NO ESTA EN COBERTURA. Decile al cliente con honestidad que todavia no operás ahí, que guardás el pedido y le avisás cuando llegues a esa zona. NO INVENTES otra zona ni le ofrezcas un proveedor.\n";
    } else if ("out_of_scope_category".equals(action)) {
      coverageHint = "\nINSTRUCCION DURA: el servicio '" + category + "' NO ESTA en la lista MVP. Decile que todavía no cubrís ese rubro y que guardás el pedido para cuando lo sumes. NO le ofrezcas un proveedor.\n";
    } else if (categoryKnown && locationKnown && providerCount == 0) {
      // Solo afirmar "no hay proveedores" cuando categoría Y zona están
      // definidas: antes se inyectaba también con datos "sin definir" y el
      // agente lo decía en el PRIMER mensaje del cliente.
      coverageHint = "\nINSTRUCCION: ahora mismo no hay proveedores libres en '" + location + "' para " + category + ". Avisá que vas a contactar apenas aparezca uno. No alarmes.\n";
    } else if (!categoryKnown || !locationKnown) {
      coverageHint = "\nINSTRUCCION: todavía faltan datos (categoría o zona). NO afirmes nada sobre disponibilidad de proveedores — ni que hay ni que no hay. Pedí el dato que falta.\n";
    }

    String customerMemory = buildCustomerMemorySection(lead);

    // Pedido ya en búsqueda (intake completo): se acabaron las preguntas.
    String waitingLine = "";
    if (lead.isReadyForMatching() && lead.getAssignedProviderId() == null) {
      waitingLine = "\nINSTRUCCION: el pedido YA está completo y en búsqueda de proveedor."
          + " NO hagas más preguntas de intake. Si el cliente aporta un dato nuevo,"
          + " agradecelo y confirmá que queda registrado; si pregunta algo, respondé"
          + " y nada más.\n";
    }

    // Detalles que el cliente YA dio (extraídos a notes en turnos previos):
    // sin esto el LLM no tiene cómo saber que su pregunta ya fue respondida
    // y re-pregunta en loop (lead #126: "¿reparación o instalación?" dos
    // veces después de que el cliente contestó "reparacion").
    String answeredLine = "";
    String notes = safe(lead.getNotes(), "");
    if (!notes.isBlank()) {
      answeredLine = "\n- Detalles que el cliente YA dio (PROHIBIDO volver a preguntarlos): "
          + notes.replace("\n", "; ") + "\n";
    }
    String priceRangeLine = buildPriceRangeLine(rawCategory, categoryKnown);
    String intakeHintLine = "";
    if (categoryKnown) {
      String hint = com.fixy.backend.model.ServiceCategory.intakeHintForId(rawCategory);
      if (hint != null) {
        // Guion de intake determinista por categoría: sin esto el 8B inventa
        // preguntas de OTRA categoría (lead #123: "¿cuántas porciones?" para
        // un aire acondicionado).
        intakeHintLine = "\nINSTRUCCION: para " + category + ", lo útil de preguntar es: " + hint
            + ". Pregunta SOLO lo que falte, de a UNA pregunta por mensaje, y NUNCA preguntes"
            + " cosas de otro rubro.\n";
      }
    }

    return """
        Contexto del pedido (interno, NO compartir IDs al cliente):
        - ID interno: %d
        - Problema reportado: %s
        - Servicio: %s
        - Zona: %s
        - Urgencia: %s
        - Datos faltantes: %s
        - Proveedores disponibles en esa zona+servicio: %d
        %s%s%s
        """.formatted(
        lead.getId(),
        safe(lead.getProblem(), ""),
        category,
        location,
        urgency,
        missing,
        providerCount,
        coverageHint,
        customerMemory,
        priceRangeLine + intakeHintLine + answeredLine + waitingLine
    );
  }

  /**
   * Cotización Estimada (Ola 2 MVP, PLAN_SUPERAPP_CLIENTE.md §Ola 2 punto 4).
   * Si la categoría está definida y tiene rango de referencia cargado en
   * ServiceCategory, se lo inyecta al LLM con instrucción de uso restringida:
   * ofrecerlo SOLO si el cliente pregunta precio o al confirmar el pedido,
   * SIEMPRE con el disclaimer de que el proveedor confirma el precio final.
   * Sin categoría conocida o sin rango cargado: string vacío — el agente debe
   * decir honestamente que el proveedor cotiza, nunca inventar un número.
   */
  private String buildPriceRangeLine(String rawCategory, boolean categoryKnown) {
    if (!categoryKnown) {
      return "";
    }
    String range = com.fixy.backend.model.ServiceCategory.priceRangeLabelForId(rawCategory);
    if (range == null) {
      return "";
    }
    return "\nINSTRUCCION: rango orientativo de precio para este servicio: " + range
        + " UYU (visita + trabajo simple). Ofrecelo SOLO si el cliente pregunta precio/cuánto"
        + " sale/cuesta/vale, o al confirmar el pedido — nunca de arranque sin que lo pida."
        + " Si el cliente PREGUNTA el precio, respondé el rango EN ESA MISMA respuesta, ANTES"
        + " de pedir zona u otro dato — nunca condiciones el precio a que primero te dé un dato"
        + " (esta instruccion le gana a 'pedí el dato que falta')."
        + " SIEMPRE aclará que es un precio de referencia y que el proveedor confirma el precio"
        + " final. NUNCA prometas ese número como precio exacto o cerrado.\n";
  }

  /**
   * Salto 1 de memoria de cliente (PLAN_SUPERAPP_CLIENTE.md / ARQUITECTURA_SUPERAPP.md
   * §1 "Salto 1"). Si este lead está vinculado a un AppUser logueado
   * (via UserLead — ver UserLeadService), arma un resumen compacto de sus
   * últimos N pedidos previos (categoría, zona, estado, si tuvo proveedor)
   * para que el agente pueda personalizar sin inventar. Cliente anónimo (sin
   * AppUser) => string vacío, cero costo extra de tokens, cero cambio de
   * comportamiento respecto a hoy.
   */
  private String buildCustomerMemorySection(Lead lead) {
    try {
      Long userId = findLinkedUserId(lead.getId());
      if (userId == null) {
        return "";
      }
      List<Lead> previous = userLeadRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
          .map(UserLead::getLeadId)
          .filter(id -> !id.equals(lead.getId()))
          .map(id -> leadRepository.findById(id).orElse(null))
          .filter(java.util.Objects::nonNull)
          .limit(CUSTOMER_MEMORY_LEAD_LIMIT)
          .toList();
      if (previous.isEmpty()) {
        return "";
      }
      StringBuilder sb = new StringBuilder("\nHistorial con este cliente (ya logueado, usalo para personalizar SIN inventar datos que no están acá):\n");
      for (Lead p : previous) {
        sb.append("- ").append(humanCategory(safe(p.getDetectedCategory(), "servicio sin definir")))
            .append(" en ").append(safe(p.getLocation(), "zona sin definir"))
            .append(", estado ").append(humanStatus(p.getStatus()));
        if (p.getAssignedProvider() != null && !p.getAssignedProvider().isBlank()) {
          sb.append(", con el proveedor ").append(p.getAssignedProvider());
        }
        sb.append(".\n");
      }
      return sb.toString();
    } catch (Exception ex) {
      log.warn("buildCustomerMemorySection failed for lead {}: {}", lead.getId(), ex.getMessage());
      return "";
    }
  }

  /** Busca el userId de AppUser vinculado a este lead, si existe. */
  private Long findLinkedUserId(Long leadId) {
    return userLeadRepository.findUserIdByLeadId(leadId).orElse(null);
  }

  private String humanStatus(com.fixy.backend.model.LeadStatus status) {
    if (status == null) return "desconocido";
    return switch (status) {
      case NEW -> "recién creado";
      case IN_REVIEW -> "en revisión";
      case PROVIDER_CONTACTED -> "contactando proveedor";
      case ASSIGNED -> "proveedor asignado";
      case IN_PROGRESS -> "en curso";
      case COMPLETED -> "completado";
      case CANCELLED -> "cancelado";
    };
  }

  /** Fuente única: com.fixy.backend.model.ServiceCategory (ver su javadoc). */
  private static final java.util.Set<String> MVP_CATEGORIES =
      java.util.Set.copyOf(com.fixy.backend.model.ServiceCategory.MVP_IDS);
  // Zonas cubiertas: fuente única en com.fixy.backend.model.CoverageZone
  // (isCovered normaliza acentos, así que "Shangrilá" y "Shangrila" son la
  // misma zona sin necesidad de listar las dos formas).

  private String deriveNextAction(Lead lead) {
    String cat = lead.getDetectedCategory() == null ? "" : lead.getDetectedCategory().toLowerCase().trim();
    String loc = lead.getLocation() == null ? "" : lead.getLocation().toLowerCase().trim();
    if (!cat.isBlank() && !"otro".equals(cat) && !MVP_CATEGORIES.contains(cat)) {
      return "out_of_scope_category";
    }
    if (!loc.isBlank() && !"sin definir".equals(loc)
        && !com.fixy.backend.model.CoverageZone.isCovered(loc)) {
      return "out_of_coverage_area";
    }
    return "ok";
  }

  private int countProvidersInZone(String category, String location) {
    if (category == null || category.isBlank() || location == null || location.isBlank()) {
      return 0;
    }
    try {
      List<ProviderCatalogItem> matches = providerCatalogService.findMatches(category, location);
      return matches == null ? 0 : matches.size();
    } catch (Exception ex) {
      return 0;
    }
  }

  private String renderHistory(List<LeadMessage> messages) {
    if (messages == null || messages.isEmpty()) {
      return "(sin mensajes previos)";
    }
    return messages.stream()
        .map(m -> "[" + roleLabel(m.getSender()) + "] " + m.getText())
        .collect(Collectors.joining("\n"));
  }

  private String roleLabel(String sender) {
    return switch (sender == null ? "" : sender.toLowerCase()) {
      case "customer" -> "cliente";
      case "fixy" -> "fixy";
      case "provider" -> "proveedor";
      default -> sender;
    };
  }

  private String fallbackGreeting(Lead lead) {
    String category = humanCategory(safe(lead.getDetectedCategory(), "tu pedido"));
    String location = safe(lead.getLocation(), "tu zona");
    String missing = humanMissing(lead.getMissingFields());
    if (!missing.isBlank()) {
      return "Hola, soy Fixy. Recibí tu pedido de %s en %s. Para conseguirte un proveedor que pase precio firme me falta %s. ¿Me lo pasás por acá?"
          .formatted(category, location, missing);
    }
    return "Hola, soy Fixy. Ya recibí tu pedido de %s en %s. Estoy buscando un proveedor disponible — te aviso por acá apenas alguien acepte.".formatted(category, location);
  }

  /** Deriva del catálogo único ServiceCategory (ver su javadoc). */
  private String humanCategory(String raw) {
    return com.fixy.backend.model.ServiceCategory.humanLabel(raw);
  }

  private String humanMissing(String missingFieldsRaw) {
    if (missingFieldsRaw == null || missingFieldsRaw.isBlank()) {
      return "";
    }
    String[] parts = missingFieldsRaw.split("\\|\\|");
    if (parts.length == 1) return parts[0];
    if (parts.length == 2) return parts[0] + " y " + parts[1];
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length - 1; i++) {
      if (i > 0) sb.append(", ");
      sb.append(parts[i]);
    }
    sb.append(" y ").append(parts[parts.length - 1]);
    return sb.toString();
  }

  private void safePost(Long leadId, String text) {
    try {
      leadMessageService.postFromAgent(leadId, text);
    } catch (Exception ex) {
      log.error("could not persist fallback agent message for lead {}: {}", leadId, ex.getMessage());
    }
  }

  private String safe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
