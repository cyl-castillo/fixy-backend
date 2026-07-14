package com.fixy.backend.service;

import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.PublicChatStartRequest;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Punto de entrada del flujo CLIENTE de WhatsApp: mensajes de un numero
 * desconocido para Fixy (no matchea ningun Provider.contactNumber) se tratan
 * como clientes conversando con el cerebro agentico, el mismo que atiende el
 * chat web (LeadAgentService).
 *
 * Criterio de "lead activo" para continuar una conversacion (documentado acá
 * porque no hay una tabla de sesiones dedicada): el lead MAS RECIENTE con el
 * mismo phone normalizado y channel="whatsapp" que NO esté en un status
 * terminal (COMPLETED o CANCELLED). Si no hay ninguno, se crea un lead nuevo.
 * Es deliberadamente simple — un cliente con un pedido viejo ya completado
 * que escribe de nuevo abre un lead nuevo, no reabre el historico.
 */
@Service
public class WhatsAppInboundService {

  private static final Logger log = LoggerFactory.getLogger(WhatsAppInboundService.class);

  private final LeadRepository leadRepository;
  private final LeadService leadService;
  private final LeadMessageService leadMessageService;
  private final LeadAgentService leadAgentService;

  public WhatsAppInboundService(
      LeadRepository leadRepository,
      LeadService leadService,
      LeadMessageService leadMessageService,
      LeadAgentService leadAgentService
  ) {
    this.leadRepository = leadRepository;
    this.leadService = leadService;
    this.leadMessageService = leadMessageService;
    this.leadAgentService = leadAgentService;
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
    Lead lead = findActiveLead(normalized);
    if (lead == null) {
      startNewConversation(normalized, text);
      return;
    }
    continueConversation(lead, text);
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

  private void startNewConversation(String from, String firstMessage) {
    LeadResponse created = leadService.createChat(new PublicChatStartRequest(null, from, "whatsapp"));
    log.info("whatsapp: nuevo lead cliente #{} desde {}", created.id(), from);
    // greet() (disparado por createChat) ya mandó el saludo fijo. El primer
    // mensaje real del cliente se procesa como cualquier turno posterior.
    Lead lead = leadRepository.findById(created.id()).orElse(null);
    if (lead == null) {
      log.warn("whatsapp: lead {} desapareció justo después de crearlo", created.id());
      return;
    }
    continueConversation(lead, firstMessage);
  }

  private void continueConversation(Lead lead, String text) {
    leadMessageService.postFromWhatsAppCustomer(lead.getId(), text);
    leadAgentService.respondToCustomerAsync(lead.getId());
  }
}
