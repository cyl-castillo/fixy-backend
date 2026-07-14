package com.fixy.backend.service;

import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeadMessageService {

  private static final Set<String> PUBLIC_SENDERS = Set.of("customer");
  private static final Set<String> PROVIDER_SENDERS = Set.of("provider", "fixy");
  private static final int MAX_TEXT_LENGTH = 2000;
  /** Detector simple de URLs y dominios visibles. Cubre http(s)://, www.foo,
   * bit.ly/algo, foo.com/bar. Falsos positivos son aceptables: el cliente
   * coordina por chat, no necesita pasar links a esta altura. */
  private static final Pattern URL_PATTERN = Pattern.compile(
      "(?i)\\b(?:https?://|www\\.|bit\\.ly|tinyurl\\.com|t\\.me|wa\\.me|" +
      "[a-z0-9-]+\\.(?:com|net|org|uy|ar|cl|br|biz|info|io|co|app|me|tk|xyz|click|link)(?:/|\\b))");

  private final LeadMessageRepository messageRepository;
  private final LeadRepository leadRepository;
  private final LeadTimelineService timelineService;
  private final WhatsAppService whatsappService;

  private final int maxMessagesPerWindow;
  private final Duration messageWindow;
  private final Map<Long, Deque<Instant>> customerPostsByLead = new ConcurrentHashMap<>();
  private final Map<Long, String> lastTextByLead = new ConcurrentHashMap<>();

  public LeadMessageService(
      LeadMessageRepository messageRepository,
      LeadRepository leadRepository,
      LeadTimelineService timelineService,
      WhatsAppService whatsappService,
      @Value("${fixy.chat.max-messages-per-window:10}") int maxMessagesPerWindow,
      @Value("${fixy.chat.window-seconds:60}") long windowSeconds
  ) {
    this.messageRepository = messageRepository;
    this.leadRepository = leadRepository;
    this.timelineService = timelineService;
    this.whatsappService = whatsappService;
    this.maxMessagesPerWindow = maxMessagesPerWindow;
    this.messageWindow = Duration.ofSeconds(windowSeconds);
  }

  public List<LeadMessageResponse> listForCustomer(Long leadId, String token) {
    Lead lead = requireLeadAndToken(leadId, token);
    return messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .map(LeadMessageResponse::fromEntity)
        .toList();
  }

  public List<LeadMessageResponse> listSinceForCustomer(Long leadId, String token, Long sinceId) {
    Lead lead = requireLeadAndToken(leadId, token);
    return messageRepository
        .findByLeadIdAndIdGreaterThanOrderByCreatedAtAsc(lead.getId(), sinceId)
        .stream()
        .map(LeadMessageResponse::fromEntity)
        .toList();
  }

  public LeadMessageResponse postFromCustomer(Long leadId, String token, String rawText) {
    Lead lead = requireLeadAndToken(leadId, token);
    String text = sanitize(rawText);
    rejectIfLooksLikeLink(text);
    rejectIfRepeatedFromCustomer(lead.getId(), text);
    enforceCustomerRateLimit(lead.getId());
    LeadMessage saved = persist(lead.getId(), "customer", text);
    lastTextByLead.put(lead.getId(), text);
    timelineService.appendEvent(lead, "MESSAGE_FROM_CUSTOMER", "user",
        text.length() > 80 ? text.substring(0, 80) + "…" : text);
    return LeadMessageResponse.fromEntity(saved);
  }

  /**
   * Persiste un mensaje generado por el agente Fixy. Saltea las validaciones
   * del cliente (rate limit, anti-link, anti-repetido) porque el agente las
   * controla en su propio servicio.
   *
   * Si el lead vino por WhatsApp (channel="whatsapp"), ademas envia el texto
   * al cliente por Cloud API. Es el unico punto donde el agente persiste sus
   * respuestas, asi que es el lugar correcto para el side-effect de salida —
   * evita duplicar el envio en cada call-site de LeadAgentService.
   */
  public LeadMessageResponse postFromAgent(Long leadId, String rawText) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    String text = sanitize(rawText);
    LeadMessage saved = persist(lead.getId(), "fixy", text);
    timelineService.appendEvent(lead, "MESSAGE_FROM_FIXY", "agent",
        text.length() > 80 ? text.substring(0, 80) + "…" : text);
    if ("whatsapp".equals(lead.getChannel()) && lead.getPhone() != null && !lead.getPhone().isBlank()) {
      whatsappService.sendText(lead.getPhone(), text);
    }
    return LeadMessageResponse.fromEntity(saved);
  }

  /**
   * Persiste un mensaje entrante de un cliente que escribe por WhatsApp.
   * Analogo a {@link #postFromCustomer} pero sin accessToken (la identidad
   * la da el numero verificado por Meta, no un token de sesion del navegador)
   * y sin el anti-link (en WhatsApp es normal que un cliente mande una
   * direccion o un link de ubicacion; el anti-link es una proteccion del
   * chat web contra spam de bots).
   */
  public LeadMessageResponse postFromWhatsAppCustomer(Long leadId, String rawText) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    String text = sanitize(rawText);
    rejectIfRepeatedFromCustomer(lead.getId(), text);
    enforceCustomerRateLimit(lead.getId());
    LeadMessage saved = persist(lead.getId(), "customer", text);
    lastTextByLead.put(lead.getId(), text);
    timelineService.appendEvent(lead, "MESSAGE_FROM_CUSTOMER", "user",
        text.length() > 80 ? text.substring(0, 80) + "…" : text);
    return LeadMessageResponse.fromEntity(saved);
  }

  /** Últimos N mensajes del lead, en orden cronológico, para feed al agente. */
  public List<LeadMessage> recentForAgent(Long leadId, int limit) {
    List<LeadMessage> all = messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    if (all.size() <= limit) {
      return all;
    }
    return all.subList(all.size() - limit, all.size());
  }

  public List<LeadMessageResponse> listForOps(Long leadId) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    return messageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()).stream()
        .map(LeadMessageResponse::fromEntity)
        .toList();
  }

  public LeadMessageResponse postFromOps(Long leadId, String sender, String rawText) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (!PROVIDER_SENDERS.contains(sender)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sender must be one of " + PROVIDER_SENDERS);
    }
    String text = sanitize(rawText);
    LeadMessage saved = persist(lead.getId(), sender, text);
    String eventType = sender.equals("provider") ? "MESSAGE_FROM_PROVIDER" : "MESSAGE_FROM_FIXY";
    timelineService.appendEvent(lead, eventType, sender,
        text.length() > 80 ? text.substring(0, 80) + "…" : text);
    return LeadMessageResponse.fromEntity(saved);
  }

  private Lead requireLeadAndToken(Long leadId, String token) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (lead.getAccessToken() == null || token == null || !lead.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    return lead;
  }

  private LeadMessage persist(Long leadId, String sender, String text) {
    if (!PUBLIC_SENDERS.contains(sender) && !PROVIDER_SENDERS.contains(sender)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid sender");
    }
    LeadMessage message = new LeadMessage();
    message.setLeadId(leadId);
    message.setSender(sender);
    message.setText(text);
    return messageRepository.save(message);
  }

  private void rejectIfLooksLikeLink(String text) {
    if (URL_PATTERN.matcher(text).find()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "no se permiten links en los mensajes; coordiná por acá");
    }
  }

  private void rejectIfRepeatedFromCustomer(Long leadId, String text) {
    String previous = lastTextByLead.get(leadId);
    if (previous != null && previous.equals(text)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "ese mensaje ya lo enviaste; esperá la respuesta");
    }
  }

  private void enforceCustomerRateLimit(Long leadId) {
    Instant now = Instant.now();
    Instant threshold = now.minus(messageWindow);
    Deque<Instant> posts = customerPostsByLead.computeIfAbsent(leadId, ignored -> new ArrayDeque<>());
    synchronized (posts) {
      while (!posts.isEmpty() && posts.peekFirst().isBefore(threshold)) {
        posts.pollFirst();
      }
      if (posts.size() >= maxMessagesPerWindow) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
            "demasiados mensajes seguidos, esperá un momento");
      }
      posts.addLast(now);
    }
  }

  private String sanitize(String text) {
    if (text == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
    }
    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
    }
    if (trimmed.length() > MAX_TEXT_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text exceeds max length");
    }
    return trimmed;
  }
}
