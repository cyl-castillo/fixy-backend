package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Qué pasa DESPUÉS de que el cliente mete un mensaje en el chat: fuente
 * única del modelo Uber. Si el proveedor ya aceptó (ASSIGNED/IN_PROGRESS) la
 * conversación es entre humanos — relay a WhatsApp del proveedor si está
 * habilitado, o aviso interino a ops por Telegram. Antes de la aceptación,
 * Fixy acompaña: turno del agente. Extraído de PublicLeadMessageController
 * para que las notas de voz (transcriptas) sigan exactamente el mismo camino
 * que el texto tipeado — un solo lugar donde vive la regla.
 */
@Service
public class CustomerMessageRouter {

  private static final Logger log = LoggerFactory.getLogger(CustomerMessageRouter.class);

  private final LeadAgentService agentService;
  private final LeadRepository leadRepository;
  private final ProviderRepository providerRepository;
  private final WhatsAppService whatsappService;
  private final TelegramNotifyService telegramNotifyService;

  public CustomerMessageRouter(
      LeadAgentService agentService,
      LeadRepository leadRepository,
      ProviderRepository providerRepository,
      WhatsAppService whatsappService,
      TelegramNotifyService telegramNotifyService
  ) {
    this.agentService = agentService;
    this.leadRepository = leadRepository;
    this.providerRepository = providerRepository;
    this.whatsappService = whatsappService;
    this.telegramNotifyService = telegramNotifyService;
  }

  /** Rutea el mensaje ya persistido del cliente: relay al proveedor aceptado, o turno del agente. */
  public void route(Long leadId, String text) {
    Lead lead = leadRepository.findById(leadId).orElse(null);
    // Modelo Uber (equipo de Carlos, 2026-08-06): Fixy acompaña al cliente
    // hasta que el proveedor ACEPTA. PROVIDER_CONTACTED = todavía esperando
    // esa aceptación — el pase de manos es en ASSIGNED.
    boolean assignedToProvider = lead != null
        && lead.getAssignedProviderId() != null
        && (lead.getStatus() == LeadStatus.ASSIGNED
            || lead.getStatus() == LeadStatus.IN_PROGRESS);
    if (!assignedToProvider) {
      agentService.respondToCustomerAsync(leadId);
      return;
    }
    if (whatsappService.isEnabled()) {
      try {
        Provider p = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
        if (p != null) {
          String to = (p.getWhatsappNumber() == null || p.getWhatsappNumber().isBlank())
              ? p.getPhone() : p.getWhatsappNumber();
          if (to != null && !to.isBlank()) {
            String customerName = lead.getName() == null ? "Cliente" : lead.getName();
            whatsappService.sendText(to, customerName + " (lead #" + lead.getId() + "): " + text);
          }
        }
      } catch (Exception ex) {
        log.warn("relay customer→provider failed: {}", ex.getMessage());
      }
    } else {
      // Interino sin WhatsApp: el proveedor no ve el mensaje salvo que tenga
      // el panel abierto — avisamos a ops (Telegram) para cerrar el loop.
      Provider p = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
      telegramNotifyService.notifyCustomerMessageForProvider(lead, p, text);
    }
  }
}
