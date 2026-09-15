package com.fixy.backend.service;

import com.fixy.backend.dto.OrderCreateResponse;
import com.fixy.backend.dto.RemoteCareOrderCreateRequest;
import com.fixy.backend.dto.RemoteCarePlanDetailResponse;
import com.fixy.backend.dto.RemoteCarePlanSummary;
import com.fixy.backend.model.CustomerPayment;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.RemoteCarePlan;
import com.fixy.backend.model.RemoteCarePlanStatus;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.CustomerPaymentRepository;
import com.fixy.backend.repository.LeadPhotoRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.RemoteCarePlanRepository;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Plan Casa a distancia (Refundación de Fixy, fase 2, contrato §B): lectura
 * pública del dueño, pedidos remotos sobre un plan {@code ACTIVE}, y las
 * mutaciones de ops (activar/pausar/cancelar/nota/visita).
 */
@Service
public class RemoteCareService {

  private static final Logger log = LoggerFactory.getLogger(RemoteCareService.class);

  private final RemoteCarePlanRepository remoteCarePlanRepository;
  private final LeadRepository leadRepository;
  private final LeadPhotoRepository leadPhotoRepository;
  private final CustomerPaymentRepository customerPaymentRepository;
  private final CustomerPaymentService customerPaymentService;
  private final ServiceCatalogItemRepository serviceCatalogItemRepository;
  private final OrderService orderService;
  private final TelegramNotifyService telegramNotifyService;
  private final String publicAppBaseUrl;
  private final String visitServiceCode;

  public RemoteCareService(
      RemoteCarePlanRepository remoteCarePlanRepository,
      LeadRepository leadRepository,
      LeadPhotoRepository leadPhotoRepository,
      CustomerPaymentRepository customerPaymentRepository,
      CustomerPaymentService customerPaymentService,
      ServiceCatalogItemRepository serviceCatalogItemRepository,
      OrderService orderService,
      TelegramNotifyService telegramNotifyService,
      @Value("${fixy.public-app-base-url:https://www.fixy.com.uy}") String publicAppBaseUrl,
      @Value("${fixy.remote-care.visit-service-code:care_visita}") String visitServiceCode
  ) {
    this.remoteCarePlanRepository = remoteCarePlanRepository;
    this.leadRepository = leadRepository;
    this.leadPhotoRepository = leadPhotoRepository;
    this.customerPaymentRepository = customerPaymentRepository;
    this.customerPaymentService = customerPaymentService;
    this.serviceCatalogItemRepository = serviceCatalogItemRepository;
    this.orderService = orderService;
    this.telegramNotifyService = telegramNotifyService;
    this.publicAppBaseUrl = publicAppBaseUrl.replaceAll("/+$", "");
    this.visitServiceCode = visitServiceCode;
  }

  // --- Público (dueño del plan) -------------------------------------------

  public RemoteCarePlan authenticate(Long id, String token) {
    return remoteCarePlanRepository.findByIdAndAccessToken(id, token)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "plan not found"));
  }

  public RemoteCarePlanDetailResponse detail(RemoteCarePlan plan) {
    List<RemoteCarePlanDetailResponse.Order> orders = leadRepository
        .findByRemoteCarePlanIdOrderByCreatedAtDesc(plan.getId()).stream()
        .map(this::toOrder)
        .toList();
    List<RemoteCarePlanDetailResponse.Payment> payments = customerPaymentRepository
        .findByRemoteCarePlanIdOrderByCreatedAtDesc(plan.getId()).stream()
        .map(p -> new RemoteCarePlanDetailResponse.Payment(
            p.getId(), p.getKind(), p.getAmount(), p.getStatus(), p.getMpPaymentLink(), p.getPaidAt()))
        .toList();

    return new RemoteCarePlanDetailResponse(
        plan.getId(),
        plan.getStatus(),
        plan.getOwnerName(),
        plan.getPropertyZone(),
        plan.getPropertyAddress(),
        new RemoteCarePlanDetailResponse.OnSite(plan.getOnSiteName(), plan.getOnSitePhone()),
        plan.getMonthlyPrice(),
        plan.getActivatedAt(),
        plan.getNextVisitNote(),
        orders,
        payments
    );
  }

  private RemoteCarePlanDetailResponse.Order toOrder(Lead lead) {
    String serviceName = lead.getServiceCode() != null
        ? serviceCatalogItemRepository.findByCode(lead.getServiceCode()).map(ServiceCatalogItem::getName).orElse(null)
        : null;
    return new RemoteCarePlanDetailResponse.Order(
        lead.getId(), serviceName, lead.getStatus(), lead.getCreatedAt(),
        leadPhotoRepository.countByLeadId(lead.getId()), lead.getAccessToken());
  }

  /**
   * Contrato §B.3: solo con plan {@code ACTIVE} — 403 con mensaje humano si
   * no ("estamos activando tu plan" para REQUESTED; mensaje propio para
   * PAUSED/CANCELLED).
   */
  public OrderCreateResponse createOrder(
      Long planId, String token, RemoteCareOrderCreateRequest request, String clientIp
  ) {
    RemoteCarePlan plan = authenticate(planId, token);
    if (plan.getStatus() != RemoteCarePlanStatus.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, humanStatusMessage(plan.getStatus()));
    }
    return orderService.createForRemoteCarePlan(request, plan, clientIp);
  }

  private String humanStatusMessage(RemoteCarePlanStatus status) {
    return switch (status) {
      case REQUESTED -> "Todavía estamos activando tu plan — te avisamos apenas esté listo.";
      case PAUSED -> "Tu plan está pausado. Escribinos por WhatsApp si querés reactivarlo.";
      case CANCELLED -> "Este plan ya no está activo. Escribinos por WhatsApp si querés uno nuevo.";
      case ACTIVE -> ""; // no aplica, ACTIVE no llega acá
    };
  }

  // --- Ops -----------------------------------------------------------------

  public List<RemoteCarePlanSummary> list() {
    return remoteCarePlanRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(RemoteCarePlanSummary::fromEntity)
        .toList();
  }

  private RemoteCarePlan requirePlan(Long id) {
    return remoteCarePlanRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "plan not found"));
  }

  /**
   * Contrato §B.3: activa el plan, crea la primera cuota mensual y avisa a
   * ops por Telegram con el link para mandarle al dueño.
   */
  public RemoteCarePlanSummary activate(Long id) {
    RemoteCarePlan plan = requirePlan(id);
    if (plan.getStatus() == RemoteCarePlanStatus.ACTIVE) {
      return RemoteCarePlanSummary.fromEntity(plan);
    }
    plan.setStatus(RemoteCarePlanStatus.ACTIVE);
    if (plan.getActivatedAt() == null) {
      plan.setActivatedAt(OffsetDateTime.now());
    }
    plan.setLastBilledAt(OffsetDateTime.now());
    RemoteCarePlan saved = remoteCarePlanRepository.save(plan);

    CustomerPayment charge = customerPaymentService.createPlanMonthlyCharge(saved);
    try {
      telegramNotifyService.notifyRemoteCarePlanCharge(saved, charge.getMpPaymentLink());
    } catch (Exception ex) {
      log.warn("telegram notify remote-care-plan-activated planId={} failed: {}", saved.getId(), ex.getMessage());
    }

    return RemoteCarePlanSummary.fromEntity(saved);
  }

  public RemoteCarePlanSummary pause(Long id) {
    RemoteCarePlan plan = requirePlan(id);
    plan.setStatus(RemoteCarePlanStatus.PAUSED);
    return RemoteCarePlanSummary.fromEntity(remoteCarePlanRepository.save(plan));
  }

  public RemoteCarePlanSummary cancel(Long id) {
    RemoteCarePlan plan = requirePlan(id);
    plan.setStatus(RemoteCarePlanStatus.CANCELLED);
    return RemoteCarePlanSummary.fromEntity(remoteCarePlanRepository.save(plan));
  }

  public RemoteCarePlanSummary updateNote(Long id, String note) {
    RemoteCarePlan plan = requirePlan(id);
    plan.setNextVisitNote(note);
    return RemoteCarePlanSummary.fromEntity(remoteCarePlanRepository.save(plan));
  }

  /**
   * Contrato §B.3, nota: visita preventiva con el servicio de precio 0
   * sembrado en V29 ({@code care_visita}). Creada por ops (no un pedido
   * público) — reusa {@link OrderService#createForRemoteCarePlan}, mismo
   * criterio "no duplicar lógica de matching" que el resto del contrato.
   */
  public OrderCreateResponse createVisit(Long id) {
    RemoteCarePlan plan = requirePlan(id);
    if (plan.getStatus() != RemoteCarePlanStatus.ACTIVE) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "el plan tiene que estar ACTIVE para crear una visita preventiva");
    }
    if (serviceCatalogItemRepository.findByCode(visitServiceCode).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "el servicio de visita preventiva ('%s') no está cargado en el catálogo".formatted(visitServiceCode));
    }
    RemoteCareOrderCreateRequest request = new RemoteCareOrderCreateRequest(
        visitServiceCode, plan.getPropertyZone(), "coordinar", null, "remote-care-visit");
    return orderService.createForRemoteCarePlan(request, plan, "ops");
  }

  public String linkFor(Long id) {
    RemoteCarePlan plan = requirePlan(id);
    return "%s/mi-casa/%d/%s".formatted(publicAppBaseUrl, plan.getId(), plan.getAccessToken());
  }
}
