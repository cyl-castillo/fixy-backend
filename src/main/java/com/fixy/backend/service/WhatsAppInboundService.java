package com.fixy.backend.service;

import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.PublicChatStartRequest;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Punto de entrada del flujo CLIENTE de WhatsApp: mensajes de un numero
 * desconocido para Fixy (no matchea ningun Provider.contactNumber) se tratan
 * como clientes. Si vienen del menu de apertura (handleMenuSelection /
 * promptFreeText, ver WhatsAppMenuService) se rutean por su seleccion; si
 * no, conversan con el cerebro agentico, el mismo que atiende el chat web
 * (LeadAgentService).
 *
 * Criterio de "lead activo" para continuar una conversacion (documentado acá
 * porque no hay una tabla de sesiones dedicada): el lead MAS RECIENTE con el
 * mismo phone normalizado y channel="whatsapp" que NO esté en un status
 * terminal (COMPLETED o CANCELLED). Si no hay ninguno, se crea un lead nuevo.
 * Es deliberadamente simple — un cliente con un pedido viejo ya completado
 * que escribe de nuevo abre un lead nuevo, no reabre el historico.
 *
 * Excepción (2026-10-13, lead #280): un vecino con un pedido WEB abierto cuyo
 * teléfono coincide (comparado normalizado: el lead guarda "093640983", Meta
 * trae "59893640983") que le escribe a Fixy —típicamente respondiendo la
 * plantilla de {@link CustomerWhatsAppNotifier}— continúa ESE pedido, y el
 * lead pasa a canal whatsapp para que el texto libre vuelva a salir por acá.
 */
@Service
public class WhatsAppInboundService {

  private static final Logger log = LoggerFactory.getLogger(WhatsAppInboundService.class);

  private final LeadRepository leadRepository;
  private final LeadService leadService;
  private final LeadMessageService leadMessageService;
  private final LeadAgentService leadAgentService;
  private final LeadTimelineService timelineService;
  private final LeadScheduleService leadScheduleService;
  private final CustomerMessageRouter customerMessageRouter;
  private final WhatsAppService whatsAppService;

  public WhatsAppInboundService(
      LeadRepository leadRepository,
      LeadService leadService,
      LeadMessageService leadMessageService,
      LeadAgentService leadAgentService,
      LeadTimelineService timelineService,
      LeadScheduleService leadScheduleService,
      CustomerMessageRouter customerMessageRouter,
      WhatsAppService whatsAppService
  ) {
    this.leadRepository = leadRepository;
    this.leadService = leadService;
    this.leadMessageService = leadMessageService;
    this.leadAgentService = leadAgentService;
    this.timelineService = timelineService;
    this.leadScheduleService = leadScheduleService;
    this.customerMessageRouter = customerMessageRouter;
    this.whatsAppService = whatsAppService;
  }

  /**
   * Procesa un mensaje de texto entrante de un numero que NO es un provider
   * conocido. from viene tal cual lo manda Meta (ya en E.164 sin "+"); se
   * normaliza igual que WhatsAppService.normalize para que el phone quede
   * consistente con el resto del sistema.
   */
  public void handleCustomerMessage(String from, String text) {
    String normalized = WhatsAppService.normalize(from);
    if (normalized == null || text == null || text.isBlank()) {
      return;
    }
    Lead lead = findActiveLeadForCustomerMessage(normalized);
    if (lead == null) {
      startNewConversation(normalized, text);
      return;
    }
    switchToWhatsAppChannel(lead, normalized);
    if (answerPendingScheduleProposal(lead, normalized, text)) {
      return;
    }
    continueConversation(lead, text);
  }

  /**
   * Procesa la selección de una fila del menú de apertura (ver
   * WhatsAppMenuService): la categoría queda fijada directo, sin pasar por
   * el clasificador de texto — no hay ambigüedad que resolver, el cliente ya
   * la eligió con un toque.
   */
  public void handleMenuSelection(String from, String categoryId) {
    String normalized = WhatsAppService.normalize(from);
    if (normalized == null) {
      return;
    }
    Lead lead = findActiveLead(normalized);
    if (lead == null) {
      lead = createBlankLead(normalized);
      if (lead == null) {
        return;
      }
    }
    leadAgentService.applyMenuCategorySelection(lead.getId(), categoryId);
  }

  /**
   * El cliente tocó "Otro / escribir" en el menú: no hay categoría que fijar.
   * Solo aseguramos que exista un lead y lo invitamos a escribir libre — sin
   * esto, el título de la fila ("Otro / escribir") se colaría como si fuera
   * la descripción real del problema si lo tratáramos como handleCustomerMessage.
   */
  public void promptFreeText(String from) {
    String normalized = WhatsAppService.normalize(from);
    if (normalized == null) {
      return;
    }
    Lead lead = findActiveLead(normalized);
    if (lead == null) {
      // greet() del createChat ya invita a escribir con sus propias palabras.
      createBlankLead(normalized);
      return;
    }
    leadMessageService.postFromAgent(lead.getId(), "Contame con tus palabras qué necesitás.");
  }

  private Lead findActiveLead(String phone) {
    List<Lead> candidates = leadRepository.findByPhoneAndChannelOrderByCreatedAtDesc(phone, "whatsapp");
    for (Lead lead : candidates) {
      if (lead.getStatus() != LeadStatus.COMPLETED && lead.getStatus() != LeadStatus.CANCELLED) {
        return lead;
      }
    }
    return null;
  }

  /** Pedidos web abiertos más viejos que esto no se "reviven" por un mensaje
   * de WhatsApp: un lead trabado en NEW hace meses no debe secuestrar una
   * conversación nueva. */
  private static final java.time.Duration WEB_LEAD_MATCH_WINDOW = java.time.Duration.ofDays(30);
  private static final List<LeadStatus> TERMINAL_STATUSES = List.of(LeadStatus.COMPLETED, LeadStatus.CANCELLED);

  /**
   * Lead al que pertenece un mensaje entrante de un vecino: el más reciente
   * entre el lead de canal whatsapp (match exacto, como siempre) y cualquier
   * lead abierto de los últimos 30 días cuyo teléfono, normalizado igual que
   * {@link WhatsAppService#normalize}, coincide. Normalizar en la búsqueda y no
   * en los datos: los teléfonos guardados no se tocan.
   */
  private Lead findActiveLeadForCustomerMessage(String normalizedPhone) {
    Lead best = findActiveLead(normalizedPhone);
    List<Lead> open = leadRepository.findOpenWithPhoneSince(
        TERMINAL_STATUSES, OffsetDateTime.now().minus(WEB_LEAD_MATCH_WINDOW));
    for (Lead candidate : open) {
      if (normalizedPhone.equals(WhatsAppService.normalize(candidate.getPhone()))) {
        if (best == null || candidate.getId() > best.getId()) {
          best = candidate;
        }
        break; // viene ordenado del más nuevo al más viejo
      }
    }
    return best;
  }

  /**
   * El vecino de un pedido web nos escribió por WhatsApp: desde ahora el lead
   * es de canal whatsapp, así {@code LeadMessageService.postFromAgent} y
   * {@code postFromProvider} le reenvían el texto libre dentro de la ventana de
   * 24 h y {@link CustomerWhatsAppNotifier} deja de mandarle plantillas.
   */
  private void switchToWhatsAppChannel(Lead lead, String normalizedPhone) {
    if ("whatsapp".equals(lead.getChannel())) {
      return;
    }
    String previous = lead.getChannel() == null || lead.getChannel().isBlank() ? "sin canal" : lead.getChannel();
    lead.setChannel("whatsapp");
    leadRepository.save(lead);
    timelineService.appendEvent(lead, CHANNEL_SWITCHED_EVENT, "system",
        "El vecino escribió por WhatsApp (" + normalizedPhone + "): el pedido pasa de " + previous
            + " a whatsapp y sigue por ahí.");
    log.info("whatsapp: lead #{} pasa de canal {} a whatsapp", lead.getId(), previous);
  }

  public static final String CHANNEL_SWITCHED_EVENT = "CHANNEL_SWITCHED_TO_WHATSAPP";

  private static final Set<String> YES_ANSWERS = Set.of(
      "si", "sii", "siii", "dale", "ok", "okey", "oka", "listo", "confirmo", "confirmado",
      "de acuerdo", "perfecto", "esta bien", "si dale", "si confirmo", "si gracias", "si ok", "si perfecto");
  private static final Set<String> NO_ANSWERS = Set.of(
      "no", "noo", "no puedo", "no me sirve", "no me queda", "no me viene", "no gracias",
      "no me queda bien", "no me sirve ese horario", "no puedo ese dia");

  /** true = acepta, false = rechaza, null = no es un SÍ/NO claro. Compara el
   * texto completo (sin acentos, minúsculas, sin puntuación ni emoji): "sí, pero
   * a las 5" NO es un sí, es una contrapropuesta para el agente / el técnico. */
  static Boolean parseScheduleAnswer(String text) {
    if (text == null) return null;
    String plain = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(java.util.Locale.ROOT)
        .replaceAll("[^a-z0-9 ]", " ")
        .replaceAll("\\s+", " ")
        .trim();
    if (YES_ANSWERS.contains(plain)) return Boolean.TRUE;
    if (NO_ANSWERS.contains(plain)) return Boolean.FALSE;
    return null;
  }

  /**
   * Respuesta determinista a "Respondé SÍ o NO por acá": si el lead tiene una
   * propuesta de horario pendiente y el mensaje es un SÍ/NO claro, va directo
   * a {@link LeadScheduleService#respond} (el mismo camino que el botón del
   * chat web) sin pasar por el agente, que no sabe de propuestas. Devuelve
   * true si el mensaje quedó atendido.
   */
  private boolean answerPendingScheduleProposal(Lead lead, String from, String text) {
    Boolean accept = parseScheduleAnswer(text);
    if (accept == null || !leadScheduleService.hasPendingProposal(lead.getId())) {
      return false;
    }
    try {
      leadMessageService.postFromWhatsAppCustomer(lead.getId(), text);
    } catch (ResponseStatusException ex) {
      // anti-repetido / rate limit del chat: el SÍ/NO igual se procesa
      log.info("whatsapp: sí/no del lead {} no se guardó en el chat: {}", lead.getId(), ex.getReason());
    }
    try {
      Map<String, Object> result = leadScheduleService.respond(lead.getId(), accept);
      String proposal = String.valueOf(result.get("proposal"));
      whatsAppService.sendText(from, accept
          ? "✅ Listo, quedó confirmado: " + proposal + ". Si necesitás algo más, escribime por acá."
          : "Entendido, ya le aviso que ese horario no te queda bien. Pueden coordinar otro por acá.");
    } catch (ResponseStatusException ex) {
      // Carrera (respondida justo antes por el chat web): que lo vea el flujo normal.
      log.info("whatsapp: la propuesta del lead {} ya no estaba pendiente: {}", lead.getId(), ex.getReason());
      customerMessageRouter.route(lead.getId(), text);
    }
    return true;
  }

  private void startNewConversation(String from, String firstMessage) {
    Lead lead = createBlankLead(from);
    if (lead == null) {
      return;
    }
    // greet() (disparado por createChat) ya mandó el saludo/menú de apertura.
    // El primer mensaje real del cliente se procesa como cualquier turno
    // posterior.
    continueConversation(lead, firstMessage);
  }

  private Lead createBlankLead(String from) {
    LeadResponse created = leadService.createChat(new PublicChatStartRequest(null, from, "whatsapp", null));
    log.info("whatsapp: nuevo lead cliente #{} desde {}", created.id(), from);
    Lead lead = leadRepository.findById(created.id()).orElse(null);
    if (lead == null) {
      log.warn("whatsapp: lead {} desapareció justo después de crearlo", created.id());
    }
    return lead;
  }

  private void continueConversation(Lead lead, String text) {
    leadMessageService.postFromWhatsAppCustomer(lead.getId(), text);
    // Mismo ruteo que el chat web: con proveedor ya asignado la conversación es
    // humano-humano (relay al técnico); antes de eso, turno del agente.
    customerMessageRouter.route(lead.getId(), text);
  }
}
