package com.fixy.backend.service;

import com.fixy.backend.dto.LeadEventResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.repository.LeadEventRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class LeadTimelineService {

  /** Mensaje saliente de WhatsApp aceptado por Meta. El mensaje del evento
   * termina en {@code (wamid=...)}: es la clave con la que el webhook cruza
   * después el resultado de entrega ({@code statuses[]}). */
  public static final String WHATSAPP_SENT = "WHATSAPP_SENT";
  /** Meta reportó {@code status=failed} para un mensaje nuestro. Incidente
   * 2026-10-08: la plantilla al técnico salió OK y nunca llegó al celular;
   * sin este evento la timeline del lead decía "contactado" y nadie sabía
   * que el técnico jamás se enteró. */
  public static final String WHATSAPP_DELIVERY_FAILED = "WHATSAPP_DELIVERY_FAILED";

  private static final String WAMID_PREFIX = "(wamid=";

  private final LeadEventRepository leadEventRepository;

  public LeadTimelineService(LeadEventRepository leadEventRepository) {
    this.leadEventRepository = leadEventRepository;
  }

  public void appendEvent(Lead lead, String type, String actor, String message) {
    LeadEvent event = new LeadEvent();
    event.setLead(lead);
    event.setType(type);
    event.setActor(actor);
    event.setMessage(message);
    leadEventRepository.save(event);
  }

  public List<LeadEventResponse> listForLead(Long leadId) {
    return leadEventRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .map(event -> new LeadEventResponse(
            event.getId(),
            event.getType(),
            event.getActor(),
            event.getMessage(),
            event.getCreatedAt()
        ))
        .toList();
  }

  /** Chequeo genérico de idempotencia: ¿ya se registró un evento de este tipo
   * para este lead? Mismo patrón que usa TelegramNotifyService internamente
   * para OPS_NOTIFIED_OPPORTUNITY; expuesto acá para que otros servicios
   * (ej. el dispatcher de acciones del agente) puedan chequear "ya pasó esto"
   * sin acoplarse directo a LeadEventRepository. */
  public boolean hasEvent(Long leadId, String type) {
    return !leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, type).isEmpty();
  }

  /** Registra un envío de WhatsApp aceptado por Meta, con el wamid al final
   * del mensaje para poder cruzarlo con el webhook de statuses. Si no hay
   * wamid (envío fallido o canal apagado) no registra nada: el "no se mandó"
   * ya queda en el log del servicio y en el evento PROVIDER_CONTACTED. */
  public void recordWhatsAppSent(Lead lead, String description, String wamid) {
    if (lead == null || wamid == null || wamid.isBlank()) return;
    appendEvent(lead, WHATSAPP_SENT, "system", whatsAppSentMessage(description, wamid));
  }

  /** Texto del evento WHATSAPP_SENT: descripción + {@code (wamid=...)} al
   * final. Único lugar que define el formato, así {@link
   * #findLeadByWhatsAppMessageId} siempre lo encuentra. */
  public static String whatsAppSentMessage(String description, String wamid) {
    return description + " " + WAMID_PREFIX + wamid + ")";
  }

  /** Lead al que pertenece un mensaje saliente de WhatsApp, por su wamid
   * (lo que Meta devuelve en {@code messages[0].id} al enviar y repite en
   * {@code statuses[].id} del webhook). Vacío si nunca se registró. */
  public Optional<Lead> findLeadByWhatsAppMessageId(String wamid) {
    if (wamid == null || wamid.isBlank()) return Optional.empty();
    return leadEventRepository
        .findFirstByTypeAndMessageContainingOrderByCreatedAtDesc(WHATSAPP_SENT, WAMID_PREFIX + wamid + ")")
        .map(LeadEvent::getLead);
  }

  /** ¿Ya hay un evento de este tipo cuyo mensaje mencione el fragmento?
   * Idempotencia fina para eventos que Meta puede reenviar (los statuses del
   * webhook se reintentan si no respondimos 200 a tiempo). */
  public boolean hasEventMentioning(String type, String fragment) {
    return leadEventRepository
        .findFirstByTypeAndMessageContainingOrderByCreatedAtDesc(type, fragment)
        .isPresent();
  }
}
