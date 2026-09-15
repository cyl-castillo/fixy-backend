package com.fixy.backend.service;

import com.fixy.backend.dto.OrderCreateRequest;
import com.fixy.backend.dto.OrderCreateResponse;
import com.fixy.backend.dto.RemoteCareOrderCreateRequest;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.OrderTimeWindow;
import com.fixy.backend.model.RemoteCarePlan;
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
 *
 * <p>Fase 2 (contrato §B.3) agrega {@link #createForRemoteCarePlan}: mismo
 * núcleo de creación, pero con {@code name/phone/remote/onSiteContact}
 * resueltos del plan en vez del request — un solo punto de creación de
 * pedidos, sin duplicar la lógica de matching ni de mensajería.
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
    boolean remote = Boolean.TRUE.equals(request.remote());
    String onSiteName = request.onSiteContact() != null ? request.onSiteContact().name() : null;
    String onSitePhone = request.onSiteContact() != null ? request.onSiteContact().phone() : null;
    boolean smoke = SmokeTraffic.marks(request.name()) || SmokeTraffic.marks(request.notes());

    return createInternal(
        request.serviceCode(), request.zone(), request.timeWindow(), request.notes(),
        request.name(), request.phone(), remote, onSiteName, onSitePhone,
        hasText(request.channel()) ? request.channel().trim() : "web-order",
        smoke, null, null, clientIp
    );
  }

  /**
   * Contrato §B.3: pedido remoto originado en un plan Casa a distancia ya
   * {@code ACTIVE} — mismo body que {@link #create} menos
   * {@code name/phone/remote/onSiteContact}, que se toman del plan.
   * {@code remote} queda SIEMPRE true (es la razón de ser del plan).
   */
  public OrderCreateResponse createForRemoteCarePlan(
      RemoteCareOrderCreateRequest request, RemoteCarePlan plan, String clientIp
  ) {
    boolean smoke = SmokeTraffic.marks(plan.getOwnerName()) || SmokeTraffic.marks(request.notes());

    return createInternal(
        request.serviceCode(), request.zone(), request.timeWindow(), request.notes(),
        plan.getOwnerName(), plan.getOwnerPhone(), true, plan.getOnSiteName(), plan.getOnSitePhone(),
        hasText(request.channel()) ? request.channel().trim() : "remote-care",
        smoke, plan.getId(), plan.getOwnerName(), clientIp
    );
  }

  private OrderCreateResponse createInternal(
      String serviceCode, String zoneLabel, String timeWindowId, String notes,
      String name, String phone, boolean remote, String onSiteName, String onSitePhone,
      String channel, boolean smoke, Long remoteCarePlanId, String remoteCarePlanOwnerName, String clientIp
  ) {
    ServiceCatalogItem service = serviceCatalogService.findOrderable(serviceCode)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "el servicio pedido no existe o no está disponible"));

    CoverageZone zone = CoverageZone.fromLabel(zoneLabel)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "esa zona todavía no está en la cobertura de Fixy"));

    OrderTimeWindow timeWindow = OrderTimeWindow.fromId(timeWindowId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "ventana horaria inválida"));

    if (!hasText(name)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    }
    if (!hasText(phone)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phone is required");
    }

    String problem = service.getName()
        + (hasText(notes) ? " — " + notes.trim() : "");
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

    Lead lead = new Lead();
    lead.setName(name.trim());
    lead.setPhone(phone.trim());
    lead.setProblem(problem);
    lead.setChannel(channel);
    lead.setDetectedCategory(service.getCategory());
    lead.setLocation(zone.label());
    lead.setUrgency(timeWindow.urgency());
    lead.setServiceCode(service.getCode());
    lead.setTimeWindow(timeWindow.id());
    lead.setRemote(remote);
    lead.setOnSiteContactName(onSiteName);
    lead.setOnSiteContactPhone(onSitePhone);
    lead.setRemoteCarePlanId(remoteCarePlanId);
    lead.setStatus(LeadStatus.NEW);
    lead.setNotes(hasText(notes) ? notes.trim() : "");
    lead.setReadyForMatching(true);
    lead.setHistory(buildHistoryEntry("Pedido estructurado creado desde %s".formatted(channel)));
    lead.setAccessToken(UUID.randomUUID().toString().replace("-", ""));

    Lead saved = leadRepository.save(lead);

    leadTimelineService.appendEvent(saved, "ORDER_CREATED", "customer",
        "Pedido: %s en %s, %s".formatted(service.getName(), zone.label(), timeWindow.label()));

    leadMessageService.postFromAgent(saved.getId(),
        buildConfirmationMessage(service, zone, timeWindow, remote, saved, remoteCarePlanId != null));

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
   * inventarse ni variar). Contrato §B.3: un pedido de plan Casa a distancia
   * agrega una línea propia en vez de la genérica de "remote".
   */
  private String buildConfirmationMessage(
      ServiceCatalogItem service, CoverageZone zone, OrderTimeWindow timeWindow, boolean remote, Lead lead,
      boolean fromRemoteCarePlan
  ) {
    StringBuilder message = new StringBuilder()
        .append("Tomé tu pedido: **").append(service.getName()).append("** en **").append(zone.label())
        .append("**, ").append(timeWindow.label()).append(". Precio orientativo desde $")
        .append(ServiceCatalogService.formatUyu(service.getPriceFrom()))
        .append(" (incluye servicio Fixy y garantía). Estoy contactando a un técnico y te aviso por acá y por WhatsApp.");

    String onSiteName = hasText(lead.getOnSiteContactName()) ? lead.getOnSiteContactName().trim() : "la persona que quede a cargo";
    if (fromRemoteCarePlan) {
      message.append(" Pedido de tu plan Casa a distancia: coordinamos con ").append(onSiteName).append(".");
    } else if (remote) {
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
