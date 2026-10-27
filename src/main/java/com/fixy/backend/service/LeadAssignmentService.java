package com.fixy.backend.service;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderOfferResponse;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderOfferRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Camino único para "un proveedor toma un lead", usado tanto por el
 * webhook de WhatsApp ({@code WhatsAppWebhookController}) como por la
 * bandeja de oportunidades self-service ({@code ProviderOpportunityService}).
 * Antes de esta clase cada canal asignaba el lead a mano (set status +
 * assignedProvider + evento + mensaje al cliente) de forma independiente,
 * lo cual iba a divergir apenas uno de los dos cambiara.
 */
@Service
public class LeadAssignmentService {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LeadAssignmentService.class);

  /** Statuses desde los que un lead puede pasar a ASSIGNED. */
  private static final List<LeadStatus> MATCHABLE_STATUSES =
      List.of(LeadStatus.NEW, LeadStatus.IN_REVIEW, LeadStatus.PROVIDER_CONTACTED);

  private final LeadRepository leadRepository;
  private final LeadTimelineService timelineService;
  private final LeadMessageService messageService;
  private final CustomerWhatsAppNotifier customerWhatsAppNotifier;
  private final ProviderOfferRepository providerOfferRepository;
  private final ProviderRepository providerRepository;

  public LeadAssignmentService(
      LeadRepository leadRepository,
      LeadTimelineService timelineService,
      LeadMessageService messageService,
      CustomerWhatsAppNotifier customerWhatsAppNotifier,
      ProviderOfferRepository providerOfferRepository,
      ProviderRepository providerRepository
  ) {
    this.leadRepository = leadRepository;
    this.timelineService = timelineService;
    this.messageService = messageService;
    this.customerWhatsAppNotifier = customerWhatsAppNotifier;
    this.providerOfferRepository = providerOfferRepository;
    this.providerRepository = providerRepository;
  }

  /**
   * Registra que el proveedor RESPONDIÓ una oferta (aceptó o rechazó): cierra
   * la oferta abierta del par (lead, proveedor) con la respuesta y actualiza
   * {@code providers.last_responded_at}. Camino único para WhatsApp y bandeja:
   * hasta 2026-10-27 solo el cambio de estado desde el panel
   * ({@code ProviderSelfService}) lo hacía, así que las 8 ofertas de prod
   * quedaron con response NULL y el ranking / medianOfferResponseMinutes no
   * veían ninguna respuesta (aceptaciones reales de los leads 277 y 280).
   * Sin oferta abierta (pozo abierto) igual cuenta como respuesta del
   * proveedor para last_responded_at.
   */
  public void recordProviderResponse(Long leadId, Provider provider, ProviderOfferResponse response) {
    OffsetDateTime now = OffsetDateTime.now();
    try {
      closeOffer(leadId, provider, response, now);
    } catch (RuntimeException ex) {
      // Es registro de métricas: nunca debe tumbar la aceptación/rechazo ya hecha.
      log.warn("no se pudo registrar la respuesta {} del proveedor {} al lead {}: {}",
          response, provider.getId(), leadId, ex.getMessage());
    }
  }

  private void closeOffer(Long leadId, Provider provider, ProviderOfferResponse response, OffsetDateTime now) {
    providerOfferRepository
        .findFirstByLeadIdAndProviderIdAndRespondedAtIsNullOrderByOfferedAtDesc(leadId, provider.getId())
        .ifPresent(offer -> {
          offer.setRespondedAt(now);
          offer.setResponse(response);
          providerOfferRepository.save(offer);
        });
    providerRepository.touchLastRespondedAt(provider.getId(), now);
    provider.setLastRespondedAt(now);
  }

  /**
   * Intenta asignar atómicamente el lead al proveedor. El primero que gana
   * la escritura en base es el que se queda con el lead: dos proveedores
   * pueden llamar esto casi simultáneamente (bandeja) y solo uno gana.
   *
   * @throws ResponseStatusException 404 si el lead no existe; 409 si el
   *     lead ya estaba asignado o cambió de status antes de esta llamada.
   */
  public Lead acceptForProvider(Long leadId, Provider provider, String eventDetail) {
    return acceptForProvider(leadId, provider, eventDetail, null);
  }

  /**
   * @param arrivalWindow Tier 2 (contrato §B.2): franja corta que el
   *                        proveedor manda al aceptar ("hoy de 14 a 18"),
   *                        opcional. Null/blank = sin franja, el cliente
   *                        recibe el mensaje genérico de siempre.
   */
  public Lead acceptForProvider(Long leadId, Provider provider, String eventDetail, String arrivalWindow) {
    int updated = leadRepository.assignIfUnclaimed(
        leadId, provider.getId(), provider.getName(), MATCHABLE_STATUSES);
    if (updated == 0) {
      if (!leadRepository.existsById(leadId)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found");
      }
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "otro proveedor tomó este trabajo primero");
    }

    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    String trimmedWindow = arrivalWindow == null ? null : arrivalWindow.trim();
    if (trimmedWindow != null && !trimmedWindow.isEmpty()) {
      lead.setArrivalWindow(trimmedWindow);
      leadRepository.save(lead);
    }
    timelineService.appendEvent(lead, "PROVIDER_ACCEPTED", "provider", eventDetail);
    recordProviderResponse(leadId, provider, ProviderOfferResponse.ACCEPTED);
    // Tier 2 (contrato §B.2): mensaje de aceptación con franja si el
    // proveedor la mandó, mismo copy que la otra ruta de aceptación
    // (ProviderSelfService.updateLeadStatus ASSIGNED desde PROVIDER_CONTACTED).
    messageService.postFromOps(lead.getId(), "fixy", acceptedMessage(provider, lead.getArrivalWindow()));
    // El vecino de un pedido web que dejó teléfono se entera por WhatsApp
    // (plantilla; lead #280). No-op para leads de canal whatsapp o sin teléfono.
    customerWhatsAppNotifier.notify(lead, provider.getName(),
        CustomerWhatsAppNotifier.acceptedNews(lead.getArrivalWindow()));
    return lead;
  }

  static String acceptedMessage(Provider provider, String arrivalWindow) {
    if (arrivalWindow != null && !arrivalWindow.isBlank()) {
      return "¡Buenas noticias! **%s** tomó tu pedido y pasa **%s**. Desde acá hablan directo."
          .formatted(provider.getName(), arrivalWindow);
    }
    return "¡Buenas noticias! **%s** tomó tu pedido. Te confirma día y hora por acá."
        .formatted(provider.getName());
  }
}
