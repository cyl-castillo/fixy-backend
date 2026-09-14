package com.fixy.backend.service;

import com.fixy.backend.dto.OrderCreateRequest;
import com.fixy.backend.dto.OrderCreateResponse;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.OrderTimeWindow;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.model.SmokeTraffic;
import com.fixy.backend.repository.LeadRepository;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pedido estructurado con precio cerrado (Refundación de Fixy, fase 1,
 * contrato REFUNDACION_FASE1_CONTRATO.md §3). Crea el {@link Lead} igual que
 * el chat conversacional pero con los campos ya resueltos de entrada (sin
 * pasar por {@code AgentService.classify}), postea la confirmación
 * determinista al cliente y dispara el matching en el acto reusando {@link
 * LeadAgentService#matchNow} — la MISMA ruta que usa el intake conversacional
 * cuando queda listo, nada duplicado.
 */
@Service
public class OrderService {

  private static final DateTimeFormatter HISTORY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final LeadRepository leadRepository;
  private final LeadTimelineService leadTimelineService;
  private final LeadMessageService leadMessageService;
  private final LeadAgentService leadAgentService;
  private final ServiceCatalogService serviceCatalogService;
  private final PublicLeadAbuseProtectionService abuseProtectionService;

  public OrderService(
      LeadRepository leadRepository,
      LeadTimelineService leadTimelineService,
      LeadMessageService leadMessageService,
      LeadAgentService leadAgentService,
      ServiceCatalogService serviceCatalogService,
      PublicLeadAbuseProtectionService abuseProtectionService
  ) {
    this.leadRepository = leadRepository;
    this.leadTimelineService = leadTimelineService;
    this.leadMessageService = leadMessageService;
    this.leadAgentService = leadAgentService;
    this.serviceCatalogService = serviceCatalogService;
    this.abuseProtectionService = abuseProtectionService;
  }

  public OrderCreateResponse create(OrderCreateRequest request, String clientIp) {
    ServiceCatalogItem service = serviceCatalogService.findOrderable(request.serviceCode())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "el servicio pedido no existe o no está disponible"));

    CoverageZone zone = CoverageZone.fromLabel(request.zone())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "esa zona todavía no está en la cobertura de Fixy"));

    OrderTimeWindow timeWindow = OrderTimeWindow.fromId(request.timeWindow())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "ventana horaria inválida"));

    boolean remote = Boolean.TRUE.equals(request.remote());
    boolean smoke = SmokeTraffic.marks(request.name()) || SmokeTraffic.marks(request.notes());

    String problem = service.getName()
        + (hasText(request.notes()) ? " — " + request.notes().trim() : "");
    if (smoke && !SmokeTraffic.marks(problem)) {
      // Preservar la marca [smoke]: todos los guards anti-tráfico-sintético
      // (schedulers, Telegram, métricas) la buscan en Lead.problem, mismo
      // criterio que LeadAgentService.applyExtractedFieldsAndReport.
      problem = "[smoke] " + problem;
    }

    // Mismo rate-limit y anti-abuso que POST /api/public/leads (contrato
    // §3): comparte el bucket por IP de PublicLeadAbuseProtectionService, no
    // uno propio.
    abuseProtectionService.validate(clientIp, problem);

    String channel = hasText(request.channel()) ? request.channel().trim() : "web-order";

    Lead lead = new Lead();
    lead.setName(request.name().trim());
    lead.setPhone(request.phone().trim());
    lead.setProblem(problem);
    lead.setChannel(channel);
    lead.setDetectedCategory(service.getCategory());
    lead.setLocation(zone.label());
    lead.setUrgency(timeWindow.urgency());
    lead.setServiceCode(service.getCode());
    lead.setTimeWindow(timeWindow.id());
    lead.setRemote(remote);
    if (request.onSiteContact() != null) {
      lead.setOnSiteContactName(request.onSiteContact().name());
      lead.setOnSiteContactPhone(request.onSiteContact().phone());
    }
    lead.setStatus(LeadStatus.NEW);
    lead.setNotes(hasText(request.notes()) ? request.notes().trim() : "");
    lead.setReadyForMatching(true);
    lead.setHistory(buildHistoryEntry("Pedido estructurado creado desde %s".formatted(channel)));
    lead.setAccessToken(UUID.randomUUID().toString().replace("-", ""));

    Lead saved = leadRepository.save(lead);

    leadTimelineService.appendEvent(saved, "ORDER_CREATED", "customer",
        "Pedido: %s en %s, %s".formatted(service.getName(), zone.label(), timeWindow.label()));

    leadMessageService.postFromAgent(saved.getId(), buildConfirmationMessage(service, zone, timeWindow, remote, saved));

    boolean contacted = leadAgentService.matchNow(saved);

    return new OrderCreateResponse(
        saved.getId(),
        saved.getAccessToken(),
        service.getCode(),
        service.getName(),
        service.getPriceFrom(),
        service.getPriceTo(),
        contacted ? "CONTACTING" : "NO_PROVIDERS"
    );
  }

  /**
   * Mensaje determinista de confirmación (contrato §3.3, "lo crítico va en
   * código no en prompt" — sin LLM de por medio, este texto no puede
   * inventarse ni variar).
   */
  private String buildConfirmationMessage(
      ServiceCatalogItem service, CoverageZone zone, OrderTimeWindow timeWindow, boolean remote, Lead lead
  ) {
    StringBuilder message = new StringBuilder()
        .append("Tomé tu pedido: **").append(service.getName()).append("** en **").append(zone.label())
        .append("**, ").append(timeWindow.label()).append(". Precio orientativo desde $")
        .append(ServiceCatalogService.formatUyu(service.getPriceFrom()))
        .append(" (incluye servicio Fixy y garantía). Estoy contactando a un técnico y te aviso por acá y por WhatsApp.");

    if (remote) {
      String onSiteName = hasText(lead.getOnSiteContactName()) ? lead.getOnSiteContactName().trim() : "la persona que quede a cargo";
      message.append(" Vas a estar fuera: coordinamos con ").append(onSiteName)
          .append(" y te mandamos fotos del antes y el después.");
    }

    return message.toString();
  }

  private String buildHistoryEntry(String message) {
    return "%s · %s".formatted(OffsetDateTime.now().format(HISTORY_FORMAT), message);
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }
}
