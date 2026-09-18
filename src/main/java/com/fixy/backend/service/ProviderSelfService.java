package com.fixy.backend.service;

import com.fixy.backend.dto.LeadResponse;
import com.fixy.backend.dto.ProviderStatsResponse;
import com.fixy.backend.model.AvailabilityWindows;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderLeadDecline;
import com.fixy.backend.model.ProviderOfferResponse;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadPhotoRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderOfferRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * API self-service del proveedor: el proveedor accede con
 * (providerId, accessToken) y opera sus leads asignados.
 *
 * Auth simple: Carlos (ops) genera el token vía
 * {@code POST /api/providers/{id}/access-token} y comparte la URL
 * {@code https://www.fixy.com.uy/p/{id}/{token}} con el proveedor por
 * WhatsApp manual.
 */
@Service
public class ProviderSelfService {

  /** Statuses que el proveedor puede setear desde su panel. */
  private static final Set<LeadStatus> PROVIDER_TRANSITIONS = Set.of(
      LeadStatus.ASSIGNED,
      LeadStatus.IN_PROGRESS,
      LeadStatus.COMPLETED,
      LeadStatus.CANCELLED
  );

  /** Tipo de evento de timeline para "voy en camino" (leído por el cliente
   *  desde el timeline público existente, contrato acordado con el otro
   *  agente que construye la superficie del cliente). */
  static final String ON_THE_WAY_EVENT_TYPE = "PROVIDER_ON_THE_WAY";

  /**
   * El pedido volvió a estar sin proveedor porque el que lo tenía canceló.
   * Lo lee {@code OrphanMatchRetryScheduler} para volver a considerarlo
   * huérfano aunque su timeline ya tenga un PROVIDER_CONTACTED viejo.
   */
  public static final String PROVIDER_RELEASED_EVENT_TYPE = "PROVIDER_RELEASED";

  /**
   * El proveedor contactado nunca respondió (ni aceptó ni rechazó) tras el
   * umbral de {@code MatchingAutoReleaseScheduler} — a diferencia de
   * {@link #PROVIDER_RELEASED_EVENT_TYPE} esto NO es un rechazo activo, así
   * que no registra {@link ProviderLeadDecline}: el pozo queda abierto y el
   * mismo proveedor puede volver a verlo (quizás estaba ocupado).
   */
  public static final String AUTO_RELEASED_EVENT_TYPE = "AUTO_RELEASED";

  /**
   * Discriminador de "cancelReason obligatorio" (choque con el frontend real,
   * 2026-08-19): el botón "No me sirve" del momento accept-decide (lead
   * auto-matcheado en PROVIDER_CONTACTED, todavía sin aceptar) pega al MISMO
   * endpoint con {@code status:CANCELLED} y sin motivo — es de bajo
   * compromiso, pasa todo el tiempo, mismo criterio que declinar desde la
   * bandeja ({@link ProviderOpportunityService#decline}), que tampoco pide
   * motivo. El diagnóstico de negocio apuntaba solo a cancelar un trabajo YA
   * comprometido, así que el motivo (y el aviso a Telegram) solo se exigen
   * cuando el status ANTERIOR a este CANCELLED es uno de estos — el
   * proveedor ya había aceptado (ASSIGNED/IN_PROGRESS) o incluso completado
   * (corrección administrativa sobre un trabajo real).
   */
  private static final Set<LeadStatus> COMMITTED_STATUSES_BEFORE_CANCEL =
      Set.of(LeadStatus.ASSIGNED, LeadStatus.IN_PROGRESS, LeadStatus.COMPLETED);

  /** Anti-spam: como máximo un aviso "voy en camino" por hora por lead. */
  private static final Duration ON_THE_WAY_COOLDOWN = Duration.ofHours(1);

  private final ProviderRepository providerRepository;
  private final LeadRepository leadRepository;
  private final LeadEventRepository leadEventRepository;
  private final LeadPhotoRepository leadPhotoRepository;
  private final ProviderLeadDeclineRepository declineRepository;
  private final ProviderOfferRepository providerOfferRepository;
  private final LeadTimelineService timelineService;
  private final CommissionService commissionService;
  private final CustomerPaymentService customerPaymentService;
  private final LeadClosingService leadClosingService;
  private final LeadMessageService leadMessageService;
  private final PushNotificationService pushNotificationService;
  private final ProviderCatalogService providerCatalogService;
  private final SearchDeadlineService searchDeadlineService;
  // @Lazy: TelegramNotifyService ya depende de ProviderSelfService (@Lazy del
  // otro lado, ver su constructor) para armar el link de panel — sin @Lazy
  // acá también se reintroduce el ciclo de beans en la creación.
  private final TelegramNotifyService telegramNotifyService;
  /** Refundación fase 2 (contrato §A.2): renombrado de "paymentsEnabled" —
   * gatea EXCLUSIVAMENTE la comisión al técnico (createForCompletedLead),
   * distinto de {@link #serviceFeeEnabled} (cargo al cliente). */
  private final boolean providerCommissionEnabled;
  /** Refundación fase 2 (contrato §A.2/§A.4.1): con true, el monto cobrado
   * sigue siendo obligatorio para COMPLETED aunque providerCommissionEnabled
   * sea false — se necesita para calcular el cargo al cliente. */
  private final boolean serviceFeeEnabled;

  public ProviderSelfService(
      ProviderRepository providerRepository,
      LeadRepository leadRepository,
      LeadEventRepository leadEventRepository,
      LeadPhotoRepository leadPhotoRepository,
      ProviderLeadDeclineRepository declineRepository,
      ProviderOfferRepository providerOfferRepository,
      LeadTimelineService timelineService,
      CommissionService commissionService,
      CustomerPaymentService customerPaymentService,
      LeadClosingService leadClosingService,
      LeadMessageService leadMessageService,
      PushNotificationService pushNotificationService,
      ProviderCatalogService providerCatalogService,
      SearchDeadlineService searchDeadlineService,
      @org.springframework.context.annotation.Lazy TelegramNotifyService telegramNotifyService,
      @Value("${fixy.payments.provider-commission-enabled:false}") boolean providerCommissionEnabled,
      @Value("${fixy.orders.service-fee-enabled:true}") boolean serviceFeeEnabled
  ) {
    this.providerRepository = providerRepository;
    this.leadRepository = leadRepository;
    this.leadEventRepository = leadEventRepository;
    this.leadPhotoRepository = leadPhotoRepository;
    this.declineRepository = declineRepository;
    this.providerOfferRepository = providerOfferRepository;
    this.timelineService = timelineService;
    this.commissionService = commissionService;
    this.customerPaymentService = customerPaymentService;
    this.leadClosingService = leadClosingService;
    this.leadMessageService = leadMessageService;
    this.pushNotificationService = pushNotificationService;
    this.providerCatalogService = providerCatalogService;
    this.searchDeadlineService = searchDeadlineService;
    this.telegramNotifyService = telegramNotifyService;
    this.providerCommissionEnabled = providerCommissionEnabled;
    this.serviceFeeEnabled = serviceFeeEnabled;
  }

  public Provider authenticate(Long providerId, String token) {
    Provider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    if (provider.getAccessToken() == null
        || token == null
        || !provider.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    return provider;
  }

  public List<Lead> assignedLeadsFor(Provider provider) {
    // Union: por ID (asignaciones nuevas) y por nombre (legacy).
    List<Lead> byId = leadRepository.findByAssignedProviderIdOrderByCreatedAtDesc(provider.getId());
    if (provider.getName() == null || provider.getName().isBlank()) {
      return byId;
    }
    List<Lead> byName = leadRepository.findByAssignedProviderIgnoreCaseOrderByCreatedAtDesc(provider.getName());
    if (byId.isEmpty()) return byName;
    if (byName.isEmpty()) return byId;
    java.util.LinkedHashMap<Long, Lead> merged = new java.util.LinkedHashMap<>();
    for (Lead l : byId) merged.put(l.getId(), l);
    for (Lead l : byName) merged.putIfAbsent(l.getId(), l);
    return new java.util.ArrayList<>(merged.values());
  }

  public Lead requireAssignedLead(Provider provider, Long leadId) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    Long assignedId = lead.getAssignedProviderId();
    if (assignedId != null && assignedId.equals(provider.getId())) {
      return lead;
    }
    String assigned = lead.getAssignedProvider();
    if (assigned != null && assigned.equalsIgnoreCase(provider.getName())) {
      return lead;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "lead not assigned to this provider");
  }

  public Lead updateLeadStatus(Provider provider, Long leadId, LeadStatus newStatus) {
    return updateLeadStatus(provider, leadId, newStatus, null);
  }

  /**
   * @param amountCharged monto que el proveedor cobró al cliente. Con
   *                       {@code fixy.payments.enabled=true}, es obligatorio
   *                       (> 0) para transicionar a COMPLETED — dispara la
   *                       creación de la comisión (H1.2/H1.3). Con el flag en
   *                       false, se ignora y el comportamiento es el mismo de
   *                       siempre (rollback seguro sin credenciales de MP).
   */
  public Lead updateLeadStatus(Provider provider, Long leadId, LeadStatus newStatus, BigDecimal amountCharged) {
    return updateLeadStatus(provider, leadId, newStatus, amountCharged, null, null);
  }

  /**
   * @param cancelReason       OBLIGATORIO (400 si falta o está vacío) SOLO
   *                            cuando {@code newStatus == CANCELLED} Y el
   *                            lead ya estaba en un status "comprometido"
   *                            (ver {@link #COMMITTED_STATUSES_BEFORE_CANCEL}) —
   *                            declinar un lead auto-matcheado ANTES de
   *                            aceptarlo (status previo PROVIDER_CONTACTED)
   *                            pega a este mismo endpoint y sigue sin exigir
   *                            motivo, igual que declinar desde la bandeja.
   *                            Si viene igual (aun sin ser obligatorio) se
   *                            persiste gratis. Valores esperados del
   *                            frontend: sin_disponibilidad | zona | precio |
   *                            otro (se persiste tal cual llega, sin validar
   *                            contra esa lista — el timeline es el registro
   *                            crudo).
   * @param cancelReasonDetail campo libre opcional, máx 300 caracteres (400
   *                            si se pasa, sin importar si el motivo era
   *                            obligatorio o no).
   */
  public Lead updateLeadStatus(
      Provider provider, Long leadId, LeadStatus newStatus, BigDecimal amountCharged,
      String cancelReason, String cancelReasonDetail
  ) {
    return updateLeadStatus(provider, leadId, newStatus, amountCharged, cancelReason, cancelReasonDetail, null);
  }

  /**
   * @param arrivalWindow Tier 2 (contrato §B.2): franja corta opcional que
   *                        el proveedor manda al aceptar ({@code newStatus
   *                        == ASSIGNED} desde {@code PROVIDER_CONTACTED});
   *                        se ignora para cualquier otra transición.
   */
  public Lead updateLeadStatus(
      Provider provider, Long leadId, LeadStatus newStatus, BigDecimal amountCharged,
      String cancelReason, String cancelReasonDetail, String arrivalWindow
  ) {
    if (!PROVIDER_TRANSITIONS.contains(newStatus)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "status not allowed for provider self-service");
    }
    // Refundación fase 2 (contrato §A.4.1): el monto es obligatorio si
    // CUALQUIERA de los dos cobros lo necesita — la comisión al técnico
    // (providerCommissionEnabled) o el cargo de servicio al cliente
    // (serviceFeeEnabled, el motivo por defecto desde esta fase). El copy
    // le habla siempre al cliente-side ("con eso calculamos el servicio
    // Fixy que paga el cliente"): es la razón que existe en el 100% de los
    // arranques de prod desde esta fase (service-fee-enabled default true).
    if ((providerCommissionEnabled || serviceFeeEnabled) && newStatus == LeadStatus.COMPLETED
        && (amountCharged == null || amountCharged.signum() <= 0)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Contanos cuánto cobraste: con eso calculamos el servicio Fixy que paga el cliente (vos no pagás nada)");
    }
    Lead lead = requireAssignedLead(provider, leadId);
    LeadStatus before = lead.getStatus();
    // Refundación fase 2 (contrato §B.4): trabajo remoto (el dueño no está)
    // exige al menos una foto SUBIDA POR EL PROVEEDOR antes de poder
    // completarlo — es la única forma que tiene el dueño de ver el trabajo.
    if (newStatus == LeadStatus.COMPLETED && lead.isRemote() && before != LeadStatus.COMPLETED
        && leadPhotoRepository.countByLeadIdAndProviderIdIsNotNull(lead.getId()) == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Subí al menos una foto del trabajo terminado: el dueño no está en la casa y es su forma de verlo.");
    }
    // Tier 1 (contrato §B.1): guards del protocolo "al llegar" — una
    // propuesta de precio pendiente bloquea el cierre; una ya aceptada pone
    // un techo a lo que se puede cobrar.
    if (newStatus == LeadStatus.COMPLETED && before != LeadStatus.COMPLETED) {
      if (lead.getProposedAt() != null && lead.getAgreedAt() == null && lead.getPriceChangeRejectedAt() == null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "El vecino todavía no aceptó el precio nuevo. Esperá su OK o cancelá la propuesta.");
      }
      if (lead.getAgreedAmount() != null && amountCharged != null
          && amountCharged.compareTo(lead.getAgreedAmount()) > 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Cobraste más de lo que el vecino aceptó ($%s)."
                .formatted(ServiceCatalogService.formatUyu(
                    lead.getAgreedAmount().setScale(0, java.math.RoundingMode.HALF_UP).intValueExact())));
      }
    }
    if (newStatus == LeadStatus.CANCELLED) {
      // Discriminador: antes de aceptar (PROVIDER_CONTACTED) es un decline de
      // bajo compromiso — pasa todo el tiempo, no exige motivo. Ya aceptado
      // (ASSIGNED/IN_PROGRESS) o incluso completado (corrección
      // administrativa) sí lo exige: ahí sí hay una promesa que se rompe.
      if (COMMITTED_STATUSES_BEFORE_CANCEL.contains(before) && (cancelReason == null || cancelReason.isBlank())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "cancelReason es obligatorio para cancelar un trabajo ya aceptado "
                + "(sin_disponibilidad|zona|precio|otro)");
      }
      if (cancelReasonDetail != null && cancelReasonDetail.length() > 300) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "cancelReasonDetail no puede superar los 300 caracteres");
      }
    }
    if (before != newStatus) {
      lead.setStatus(newStatus);
      provider.setLastRespondedAt(OffsetDateTime.now());
      timelineService.appendEvent(lead, "PROVIDER_STATUS_CHANGE", "provider",
          "%s → %s".formatted(before, newStatus));
      // bumps de contadores en el provider para visibilidad ops
      switch (newStatus) {
        case ASSIGNED -> {
          provider.setAcceptedJobsCount(safeInc(provider.getAcceptedJobsCount()));
          // Tier 2 (contrato §B.2): franja opcional que manda el proveedor
          // al aceptar — misma columna que pisan la confirmación de
          // horario (LeadScheduleService) y "voy en camino" con ETA.
          String trimmedArrivalWindow = arrivalWindow == null ? null : arrivalWindow.trim();
          if (trimmedArrivalWindow != null && !trimmedArrivalWindow.isEmpty()) {
            lead.setArrivalWindow(trimmedArrivalWindow);
          }
          // Momento Uber (equipo de Carlos 2026-08-06): la ACEPTACIÓN es la
          // noticia que el cliente espera — antes este camino (automatch →
          // "Aceptar el trabajo" en el panel) no le decía nada y el pase de
          // manos era invisible. El camino de la bandeja ya lo hacía
          // (LeadAssignmentService.acceptForProvider); ahora los dos, con el
          // mismo copy (contrato §B.2, ver LeadAssignmentService.acceptedMessage).
          if (before == LeadStatus.PROVIDER_CONTACTED || before == LeadStatus.NEW) {
            String news = LeadAssignmentService.acceptedMessage(provider, lead.getArrivalWindow());
            leadMessageService.postFromOps(lead.getId(), "fixy", news);
            try {
              pushNotificationService.notifyLeadHasNews(lead.getId(), "¡Aceptaron tu pedido!", news);
            } catch (Exception ex) {
              // best-effort, como el resto de los push
            }
          }
          if (before == LeadStatus.PROVIDER_CONTACTED) {
            // Tier 2 (contrato §A.2): cierra la oferta abierta del par como
            // ACCEPTED — solo existe oferta cuando el camino fue el
            // auto-match (PROVIDER_CONTACTED), no el pozo abierto (NEW).
            closeOpenOfferAsAccepted(lead.getId(), provider.getId());
          }
        }
        case CANCELLED -> provider.setRejectedJobsCount(safeInc(provider.getRejectedJobsCount()));
        case COMPLETED -> {
          // Métricas honestas (hallazgo 2026-08-17, lead #200): solo el
          // tráfico [smoke] no cuenta acá. Un cierre REAL con comisión
          // condonada (WAIVED, cortesía comercial) sigue siendo un trabajo
          // real — no se excluye por WAIVED, mismo criterio documentado en
          // OpsMetricsService.dailyMetrics.
          if (!com.fixy.backend.model.SmokeTraffic.marks(lead.getProblem())) {
            provider.setCompletedJobsCount(safeInc(provider.getCompletedJobsCount()));
          }
        }
        default -> { /* no counter */ }
      }
      // Que un proveedor no pueda NO mata el pedido del cliente: se libera y
      // vuelve a búsqueda (ver releaseAfterProviderCancel). Excepción: cancelar
      // un trabajo YA COMPLETADO es una corrección administrativa, no un
      // rechazo — resucitar ahí le buscaría proveedor a un trabajo hecho (y
      // con payments ON la comisión ya existe).
      if (newStatus == LeadStatus.CANCELLED && before != LeadStatus.COMPLETED) {
        releaseAfterProviderCancel(lead, provider, before, cancelReason, cancelReasonDetail);
      }
      leadRepository.save(lead);
      providerRepository.save(provider);
      if (providerCommissionEnabled && newStatus == LeadStatus.COMPLETED) {
        commissionService.createForCompletedLead(lead, provider, amountCharged);
      }
      // Tier 1 (contrato §C.2): el cliente eligió "solo el técnico, sin
      // garantía" al reservar — no se crea CustomerPayment para este lead
      // nunca, sin importar cuánto cobró el técnico.
      boolean serviceFeeOptOut = lead.isServiceFeeOptOut();
      if (serviceFeeEnabled && newStatus == LeadStatus.COMPLETED && amountCharged != null && !serviceFeeOptOut) {
        // Refundación fase 2 (contrato §A.4.1-3): cargo de servicio al
        // cliente — mensaje propio con el link de pago, independiente del
        // aviso de comisión (provider_only, arriba) y del de confirmación
        // (abajo, siempre se manda).
        customerPaymentService.createServiceFeeForCompletedLead(lead, provider, amountCharged);
      }
      if (newStatus == LeadStatus.COMPLETED) {
        if (serviceFeeOptOut) {
          // Contrato §C.2: mensaje ÚNICO y distinto — no hay cargo de
          // servicio pendiente, así que no tiene sentido pedir "confirmá
          // que quedó todo bien para activar la garantía" (no hay garantía).
          String technicianName = hasText(provider.getName()) ? provider.getName() : "El técnico";
          // Tier 2 (contrato §C.1): mismo cierre que notifyCustomerOfCompletion
          // — "Confirmá acá arriba si quedó todo bien" en vez de mencionar
          // estrellas (el score ahora es opcional en la confirmación).
          leadMessageService.postFromOps(lead.getId(), "fixy",
              ("%s marcó el trabajo como terminado. Elegiste sin garantía Fixy: no hay nada más que pagar. "
                  + "Confirmá acá arriba si quedó todo bien.").formatted(technicianName));
        } else {
          // Un solo mensaje: si algún cobro está ON, ya mandó su propio
          // aviso (provider_only para la comisión, al cliente para el cargo
          // de servicio). Este es SIEMPRE al cliente, pidiendo
          // confirmación/rating.
          leadClosingService.notifyCustomerOfCompletion(lead);
        }
      }
    }
    return lead;
  }

  /**
   * El proveedor canceló un trabajo que tenía tomado. Antes esto dejaba el
   * lead en CANCELLED terminal y en silencio: el cliente del #128 leyó
   * "¡Buenas noticias! Apareció un proveedor" a las 14:29 del 2026-07-29 y a
   * las 14:46 su pedido estaba muerto sin un solo mensaje; al del #187,
   * Guillermo de Carnot Clima le escribió presentándose y dos horas después
   * canceló, también sin aviso. Un rechazo del proveedor no es una decisión
   * del cliente y no tiene por qué terminar su pedido.
   *
   * Ahora el pedido: (1) registra el rechazo, para que el matching no se lo
   * vuelva a ofrecer al mismo proveedor — el mismo registro que usa la
   * bandeja; (2) se libera y vuelve a NEW, así el reintento puede buscarle
   * otro; (3) se lo cuenta al cliente con todas las letras.
   *
   * Converge solo: cada proveedor que cancela queda registrado, así que la
   * lista de candidatos se achica en cada vuelta y nunca hay ping-pong. Si
   * se agotan, el pedido queda esperando en NEW sin spamear a nadie (el
   * reintento calla cuando no hay a quién ofrecer).
   */
  /**
   * Historial "no concretados" del panel (feedback de Guillermo vía Carlos
   * 2026-08-05): las oportunidades que el proveedor soltó, con qué pasó
   * después. Sin datos sensibles del cliente (el pedido pudo pasar a otro).
   * Se excluye el tráfico de prueba y se limita a las últimas 20.
   */
  public List<com.fixy.backend.dto.ProviderDeclinedLeadSummary> declinedLeadsFor(Provider provider) {
    return declineRepository.findByProviderId(provider.getId()).stream()
        .sorted(java.util.Comparator.comparing(
            com.fixy.backend.model.ProviderLeadDecline::getCreatedAt,
            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
        .limit(20)
        .map(decline -> {
          Lead lead = leadRepository.findById(decline.getLeadId()).orElse(null);
          if (lead == null || com.fixy.backend.model.SmokeTraffic.marks(lead.getProblem())) {
            return null;
          }
          String outcome;
          if (lead.getStatus() == LeadStatus.COMPLETED) {
            outcome = "completado";
          } else if (lead.getStatus() == LeadStatus.CANCELLED) {
            outcome = "cancelado";
          } else if (lead.getAssignedProviderId() != null) {
            outcome = "tomado_por_otro";
          } else {
            outcome = "en_busqueda";
          }
          String problem = lead.getProblem() == null ? "" : lead.getProblem();
          return new com.fixy.backend.dto.ProviderDeclinedLeadSummary(
              lead.getId(),
              lead.getDetectedCategory(),
              lead.getLocation(),
              problem.length() > 120 ? problem.substring(0, 120) + "…" : problem,
              decline.getCreatedAt(),
              outcome);
        })
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  /**
   * @param before              status del lead ANTES de este CANCELLED — el
   *                            discriminador de "ya comprometido"
   *                            ({@link #COMMITTED_STATUSES_BEFORE_CANCEL}).
   *                            Determina si el motivo era obligatorio y, más
   *                            importante acá, si esto es noticia para
   *                            Telegram (un decline pre-aceptación NO lo es:
   *                            pasa todo el tiempo y sería spam).
   * @param cancelReason        obligatorio si {@code before} es un status
   *                            comprometido (ya validado por
   *                            {@link #updateLeadStatus}); puede venir null
   *                            en un decline pre-aceptación.
   * @param cancelReasonDetail  opcional, ya validado <=300 chars.
   */
  private void releaseAfterProviderCancel(
      Lead lead, Provider provider, LeadStatus before, String cancelReason, String cancelReasonDetail
  ) {
    if (lead.getId() != null && provider.getId() != null
        && !declineRepository.existsByLeadIdAndProviderId(lead.getId(), provider.getId())) {
      ProviderLeadDecline decline = new ProviderLeadDecline();
      decline.setLeadId(lead.getId());
      decline.setProviderId(provider.getId());
      declineRepository.save(decline);
    }
    // Tier 2 (contrato §A.2): release explícito del proveedor cierra la
    // oferta abierta del par como DECLINED.
    closeOpenOfferAsDeclined(lead.getId(), provider.getId());

    boolean wasCommitted = COMMITTED_STATUSES_BEFORE_CANCEL.contains(before);
    String providerName = hasText(provider.getName()) ? provider.getName() : "El proveedor";
    clearAssignment(lead);
    // Tier 2 (contrato §B.3): vuelve al pozo → se reinicia la hora límite de
    // búsqueda (el pedido sigue vivo, la promesa de "hasta las HH:mm" tiene
    // que arrancar de nuevo desde este momento).
    searchDeadlineService.begin(lead);

    // Motivo + detalle en el evento existente (sin tabla nueva): el timeline
    // es el registro crudo, sin traducir el código de motivo. cancelReason
    // puede venir null en un decline pre-aceptación (no era obligatorio) —
    // si vino igual, se persiste gratis.
    String verb = wasCommitted ? "canceló" : "declinó";
    String reasonSuffix = hasText(cancelReason)
        ? " [motivo: %s%s]".formatted(cancelReason,
            hasText(cancelReasonDetail) ? " — \"%s\"".formatted(cancelReasonDetail.trim()) : "")
        : "";
    timelineService.appendEvent(lead, PROVIDER_RELEASED_EVENT_TYPE, "system",
        "%s %s%s: el pedido vuelve a búsqueda de proveedor".formatted(providerName, verb, reasonSuffix));

    // ¿Queda alguien más que pueda tomarlo? El decline ya se guardó arriba,
    // así que findMatchesForLead excluye a quien acaba de rechazar: la
    // respuesta es exactamente "quién más hay", no "quién había".
    boolean hasAlternative = hasAlternativeProvider(lead);

    // Prometer "ya estoy buscando a otra persona" cuando NO hay otra persona
    // es la promesa vacía que mata el pedido en silencio (embudo 2026-09-01:
    // #255 y #256 de pastelería los rechazó la única pastelera el 27/08, #260
    // de aires el único técnico el 28/08 — los tres siguen NEW, sin un solo
    // mensaje desde entonces, porque retryAutoMatch calla a propósito cuando
    // no hay match). Si no hay nadie, se dice la verdad y se avisa al equipo.
    String message = hasAlternative
        ? ("%s al final no va a poder tomar tu pedido. Ya estoy buscando a otra persona "
            + "y te aviso por acá apenas tenga novedades.").formatted(providerName)
        : ("%s al final no va a poder tomar tu pedido, y por ahora no tengo a nadie más "
            + "libre para %s en %s. Ya avisé al equipo para conseguirte a alguien y te "
            + "aviso por acá apenas lo tenga.")
            .formatted(providerName, humanCategory(lead), safeZone(lead));
    leadMessageService.postFromAgent(lead.getId(), message);
    try {
      pushNotificationService.notifyLeadHasNews(lead.getId(), "Novedades de tu pedido", message);
    } catch (Exception ex) {
      // best-effort, nunca debe romper el flujo (mismo patrón que el resto de push)
    }
    // Telegram solo para cancelaciones de trabajo YA comprometido: un
    // decline pre-aceptación no es noticia (pasa todo el tiempo) y sería
    // spam para Carlos.
    if (wasCommitted) {
      try {
        telegramNotifyService.notifyProviderCancelled(lead, provider, cancelReason, cancelReasonDetail);
      } catch (Exception ex) {
        // best-effort: un aviso a ops que falla no debe romper la cancelación
      }
    }
    // Demanda sin oferta SÍ es noticia, incluso en un decline pre-aceptación:
    // es el único momento en que se sabe que el pedido se quedó sin nadie, y
    // es accionable (aprobar a quien está pendiente, o salir a captar). El
    // guard anti-smoke vive dentro de notifyDemandWithoutSupply.
    if (!hasAlternative) {
      try {
        telegramNotifyService.notifyDemandWithoutSupply(lead);
      } catch (Exception ex) {
        // best-effort: un aviso a ops que falla no debe romper la cancelación
      }
    }
  }

  /**
   * ¿Hay algún otro proveedor que todavía pueda tomar este pedido? Se llama
   * DESPUÉS de registrar el decline, así que quien acaba de rechazar ya está
   * excluido. Ante cualquier fallo devuelve {@code true}: el costo de un
   * falso "hay alguien" es un mensaje optimista de más; el de un falso "no
   * hay nadie" es un aviso equivocado a Carlos.
   */
  private boolean hasAlternativeProvider(Lead lead) {
    try {
      return !providerCatalogService
          .findMatchesForLead(lead.getId(), lead.getDetectedCategory(), lead.getLocation())
          .isEmpty();
    } catch (Exception ex) {
      return true;
    }
  }

  private String humanCategory(Lead lead) {
    String category = lead.getDetectedCategory();
    return hasText(category) ? category.replace('_', ' ') : "lo que pediste";
  }

  private String safeZone(Lead lead) {
    String zone = lead.getLocation();
    return hasText(zone) ? zone : "tu zona";
  }

  private void clearAssignment(Lead lead) {
    lead.setAssignedProviderId(null);
    lead.setAssignedProvider(null);
    lead.setStatus(LeadStatus.NEW);
  }

  /** Tier 2 (contrato §A.2): cierra como ACCEPTED la oferta abierta del par
   * (lead, proveedor). No-op si no hay oferta registrada. */
  private void closeOpenOfferAsAccepted(Long leadId, Long providerId) {
    providerOfferRepository
        .findFirstByLeadIdAndProviderIdAndRespondedAtIsNullOrderByOfferedAtDesc(leadId, providerId)
        .ifPresent(offer -> {
          offer.setRespondedAt(OffsetDateTime.now());
          offer.setResponse(ProviderOfferResponse.ACCEPTED);
          providerOfferRepository.save(offer);
        });
  }

  /** Tier 2 (contrato §A.2): cierra como DECLINED la oferta abierta del par
   * (lead, proveedor) — release explícito del proveedor. No-op si no hay
   * oferta registrada (ej. cancelación de un lead tomado desde el pozo,
   * donde nunca hubo oferta). */
  private void closeOpenOfferAsDeclined(Long leadId, Long providerId) {
    providerOfferRepository
        .findFirstByLeadIdAndProviderIdAndRespondedAtIsNullOrderByOfferedAtDesc(leadId, providerId)
        .ifPresent(offer -> {
          offer.setRespondedAt(OffsetDateTime.now());
          offer.setResponse(ProviderOfferResponse.DECLINED);
          providerOfferRepository.save(offer);
        });
  }

  /**
   * Auto-liberación (mejora "nunca más camino muerto" v2, 2026-08-19): el
   * proveedor contactado no aceptó NI rechazó tras el umbral de
   * {@code MatchingAutoReleaseScheduler}. A diferencia de
   * {@link #releaseAfterProviderCancel} esto NO es un rechazo activo — no
   * registra {@link ProviderLeadDecline} a propósito, así que el mismo
   * proveedor puede volver a ver este lead en su bandeja (quizás estaba
   * ocupado; el pozo es abierto).
   */
  public Lead releaseAfterTimeout(Lead lead, Provider unresponsiveProvider) {
    String providerName = hasText(unresponsiveProvider.getName())
        ? unresponsiveProvider.getName() : "El proveedor contactado";
    clearAssignment(lead);
    // Tier 2 (contrato §B.3): vuelve al pozo → se reinicia la hora límite.
    searchDeadlineService.begin(lead);

    timelineService.appendEvent(lead, AUTO_RELEASED_EVENT_TYPE, "system",
        "%s (id %d) no respondió a tiempo: el pedido vuelve a búsqueda abierta"
            .formatted(providerName, unresponsiveProvider.getId()));

    String message = "Seguimos buscando quién pueda tomar tu pedido — apenas confirme un "
        + "proveedor, te aviso por acá.";
    // Repetición deliberada: el texto es el mismo pero el hecho es nuevo (se
    // cayó OTRO proveedor por timeout), y el push sale igual — el chat no
    // puede quedar sin la línea que explica ese push.
    leadMessageService.postFromAgentAllowingRepeat(lead.getId(), message);
    try {
      pushNotificationService.notifyLeadHasNews(lead.getId(), "Seguimos con tu pedido", message);
    } catch (Exception ex) {
      // best-effort, nunca debe romper el flujo (mismo patrón que el resto de push)
    }
    return leadRepository.save(lead);
  }

  /**
   * "Voy en camino" (caso real: Nueva Era escribió "en 40 min maso llega"
   * como texto perdido en el chat, lead #105 — esto lo convierte en una
   * acción de un toque, como Uber). Efectos: evento de timeline
   * PROVIDER_ON_THE_WAY (el cliente lo lee del timeline público existente),
   * mensaje al chat (audience=all, visible para el cliente) y push. Anti-spam:
   * máximo un aviso por hora por lead, chequeado contra el evento más
   * reciente del mismo tipo — evita que un toque doble o un proveedor
   * ansioso spamee al cliente.
   *
   * @param etaMinutes opcional; si viene, se menciona en el mensaje.
   */
  public Lead notifyOnTheWay(Provider provider, Long leadId, Integer etaMinutes) {
    Lead lead = requireAssignedLead(provider, leadId);

    List<LeadEvent> recent = leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, ON_THE_WAY_EVENT_TYPE);
    if (!recent.isEmpty()) {
      OffsetDateTime lastSentAt = recent.get(0).getCreatedAt();
      if (lastSentAt != null && lastSentAt.isAfter(OffsetDateTime.now().minus(ON_THE_WAY_COOLDOWN))) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
            "ya le avisaste al cliente hace poco; esperá un rato antes de avisar de nuevo");
      }
    }

    String providerName = hasText(provider.getName()) ? provider.getName() : "Tu proveedor";
    String etaSuffix = etaMinutes != null && etaMinutes > 0
        ? " — llega en ~%d min".formatted(etaMinutes)
        : "";
    String message = "🚛 %s avisó que va en camino%s".formatted(providerName, etaSuffix);

    // Tier 2 (contrato §B.2.c): con ETA, pisa la franja — "hoy, llega en
    // ~N min" es más preciso que cualquier franja anterior.
    if (etaMinutes != null && etaMinutes > 0) {
      lead.setArrivalWindow("hoy, llega en ~%d min".formatted(etaMinutes));
      leadRepository.save(lead);
    }

    timelineService.appendEvent(lead, ON_THE_WAY_EVENT_TYPE, "provider", message);
    leadMessageService.postFromOps(lead.getId(), "provider", message, "all");
    try {
      pushNotificationService.notifyLeadHasNews(lead.getId(), providerName + " va en camino", message);
    } catch (Exception ex) {
      // best-effort, nunca debe romper el flujo (mismo patrón que el resto de push)
    }
    return lead;
  }

  /**
   * "Horario acordado con un toque" (tanda flujo 2026-07-21): la
   * coordinación de día/hora era 100% chat libre — ni el sistema ni el
   * cliente quedaban con un horario concreto. El proveedor propone desde
   * chips en su panel; la propuesta viaja como evento SCHEDULE_PROPOSED
   * (message = la etiqueta cruda, ej. "mañana de 14 a 16" — el cliente la
   * confirma/rechaza vía {@link LeadScheduleService}) + mensaje de chat +
   * push, mismo trío que "voy en camino".
   *
   * Una nueva propuesta reemplaza a la anterior (el evento más reciente
   * manda) — no hay límite de propuestas, pero sí el anti-doble-toque de
   * 1 min para no duplicar por taps repetidos.
   */
  public Lead proposeSchedule(Provider provider, Long leadId, String rawProposal) {
    Lead lead = requireAssignedLead(provider, leadId);
    String proposal = rawProposal == null ? "" : rawProposal.trim();
    if (proposal.length() < 3 || proposal.length() > 120) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "la propuesta de horario debe tener entre 3 y 120 caracteres");
    }

    List<LeadEvent> recent = leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, LeadScheduleService.SCHEDULE_PROPOSED_EVENT_TYPE);
    if (!recent.isEmpty()) {
      OffsetDateTime lastSentAt = recent.get(0).getCreatedAt();
      if (lastSentAt != null && lastSentAt.isAfter(OffsetDateTime.now().minusMinutes(1))) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
            "acabás de proponer un horario; esperá un momento antes de proponer otro");
      }
    }

    String providerName = hasText(provider.getName()) ? provider.getName() : "Tu proveedor";
    timelineService.appendEvent(lead, LeadScheduleService.SCHEDULE_PROPOSED_EVENT_TYPE, "provider", proposal);
    leadMessageService.postFromOps(lead.getId(), "provider",
        "📅 %s propone pasar %s. Si te sirve, confirmalo acá en el chat con un toque.".formatted(providerName, proposal),
        "all");
    try {
      pushNotificationService.notifyLeadHasNews(lead.getId(), providerName + " propuso un horario",
          "%s — confirmalo con un toque desde el chat.".formatted(proposal));
    } catch (Exception ex) {
      // best-effort, nunca debe romper el flujo (mismo patrón que el resto de push)
    }
    return lead;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }

  /**
   * Disponibilidad MVP (agenda/disponibilidad, base de Ola 2): el proveedor
   * la setea desde su panel con el mismo token de self-service. En pausa
   * ({@code acceptingWork=false}) filtra al proveedor de
   * {@link ProviderOpportunityService#listFor} — no afecta trabajos ya
   * asignados, solo oportunidades nuevas.
   */
  public Provider setAcceptingWork(Provider provider, boolean acceptingWork) {
    provider.setAcceptingWork(acceptingWork);
    return providerRepository.save(provider);
  }

  /**
   * Tier 2 (contrato §A.4): {@code PATCH /availability} ahora acepta
   * {@code acceptingWork} y/o {@code availabilityWindows} — al menos uno
   * presente (400 si ninguno, validado en el controller). {@code
   * availabilityWindows} vacío borra la ventana (vuelve a "siempre
   * disponible"); se valida el formato con {@link AvailabilityWindows#parse}
   * antes de persistir (400 con mensaje claro si está mal escrito).
   */
  public Provider updateAvailability(Provider provider, Boolean acceptingWork, String availabilityWindows) {
    if (acceptingWork != null) {
      provider.setAcceptingWork(acceptingWork);
    }
    if (availabilityWindows != null) {
      // Solo para validar — tira 400 si el formato es inválido; lo que se
      // persiste es el string crudo (tal como lo mandó el proveedor), no
      // format() de vuelta, para no reordenar/normalizar de más lo que
      // escribió.
      AvailabilityWindows.parse(availabilityWindows);
      provider.setAvailabilityWindows(availabilityWindows.isBlank() ? null : availabilityWindows.trim());
    }
    return providerRepository.save(provider);
  }

  public Provider regenerateAccessToken(Long providerId) {
    Provider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    provider.setAccessToken(newAccessToken());
    return providerRepository.save(provider);
  }

  /**
   * Devuelve el provider con accessToken garantizado: si ya tiene uno, lo
   * deja intacto (no rota el link que ops ya pudo haber compartido); si es
   * null, genera uno nuevo. Usado por avisos automáticos (Telegram) que
   * necesitan el link de panel sin invalidar tokens ya entregados.
   */
  /**
   * Resumen público del proveedor asignado a un lead, para
   * {@code LeadResponse.assignedProviderSummary} (contrato acordado con el
   * agente que construye la superficie del cliente). Null si el lead no
   * tiene proveedor asignado o el proveedor no existe más. Honestidad de
   * reputación: {@code ratingAverage} null si {@code ratingCount == 0},
   * mismo criterio que {@link ProviderCatalogService#publicPreview}.
   */
  public LeadResponse.AssignedProviderSummary summaryForAssignedProvider(Lead lead) {
    if (lead == null || lead.getAssignedProviderId() == null) {
      return null;
    }
    Provider provider = providerRepository.findById(lead.getAssignedProviderId()).orElse(null);
    if (provider == null) {
      return null;
    }
    int ratingCount = provider.getRatingCount() == null ? 0 : provider.getRatingCount();
    Double ratingAverage = ratingCount == 0 ? null : provider.getRatingAverage();
    return new LeadResponse.AssignedProviderSummary(
        provider.getName(),
        ratingAverage,
        ratingCount,
        provider.getCompletedJobsCount(),
        provider.getPrimaryZone(),
        // Teléfono para el botón Llamar del cliente (UX 2026-08): recién
        // post-asignación — nuestro modelo no castiga el contacto directo.
        provider.getPhone(),
        providerCatalogService.reviewSnippetsFor(provider.getId()),
        provider.getPhotoUrl(),
        lead.getArrivalWindow()
    );
  }

  public Provider ensureAccessToken(Long providerId) {
    Provider provider = providerRepository.findById(providerId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
    if (provider.getAccessToken() == null || provider.getAccessToken().isBlank()) {
      provider.setAccessToken(newAccessToken());
      return providerRepository.save(provider);
    }
    return provider;
  }

  private String newAccessToken() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private int safeInc(Integer value) {
    return value == null ? 1 : value + 1;
  }

  /**
   * "Mi perfil" (self-service, Ola 2): SOLO estos campos son editables por
   * el proveedor. Deliberadamente no toca status, rating, comisiones ni
   * categories — eso rompe matching y lo gestiona ops. Validación de
   * tamaños ya la hace {@code @Valid} en el controller; acá solo se
   * defiende contra blank tras trim (Bean Validation no lo hace por sí
   * solo con {@code @NotBlank} si el string trae solo espacios raros, pero
   * esto es belt-and-suspenders).
   */
  public Provider updateProfile(
      Provider provider,
      String name,
      String description,
      String coverageZones,
      String phone
  ) {
    String trimmedName = name == null ? "" : name.trim();
    if (trimmedName.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "el nombre no puede estar vacío");
    }
    provider.setName(trimmedName);
    provider.setDescription(blankToNull(description));
    provider.setCoverageZones(blankToNull(coverageZones));
    if (hasText(phone)) {
      provider.setPhone(phone.trim());
    }
    return providerRepository.save(provider);
  }

  private String blankToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * "Mis números" (self-service, Ola 2): estadísticas derivadas de datos
   * que ya existen, sin tabla nueva. Tasa de aceptación = aceptados /
   * (aceptados + rechazados) sobre los contadores que
   * {@link #updateLeadStatus} ya viene incrementando desde que existe el
   * panel de proveedor — null si todavía no hay ninguno de los dos (nunca
   * 0% dramático para un proveedor nuevo). Completados por semana: últimas
   * 4 semanas (lunes a lunes), fechado por el evento de timeline
   * PROVIDER_STATUS_CHANGE "→ COMPLETED" más reciente de cada lead
   * asignado — el mismo patrón documentado en
   * {@code LeadEventRepository.findByLeadIdInOrderByLeadIdAscCreatedAtAsc}
   * porque {@code lead.updatedAt} se pisa con cualquier cambio posterior
   * (ej. un mensaje de chat después de completar).
   */
  public ProviderStatsResponse statsFor(Provider provider) {
    Integer accepted = provider.getAcceptedJobsCount();
    Integer rejected = provider.getRejectedJobsCount();
    int acceptedCount = accepted == null ? 0 : accepted;
    int rejectedCount = rejected == null ? 0 : rejected;
    int totalDecisions = acceptedCount + rejectedCount;
    Double acceptanceRate = totalDecisions == 0 ? null : (double) acceptedCount / totalDecisions;

    int ratingCount = provider.getRatingCount() == null ? 0 : provider.getRatingCount();
    Double ratingAverage = ratingCount == 0 ? null : provider.getRatingAverage();

    List<ProviderStatsResponse.WeeklyCompleted> completedByWeek = completedByWeek(provider);

    // Tier 2 (contrato §A.4): velocidad de respuesta — null con menos de 3
    // ofertas en ventana (mismo criterio de honestidad que acceptanceRate/
    // ratingAverage null: nunca un "0" o un "lento" dramático para quien
    // recién arranca).
    int responseSampleSize = providerCatalogService.responseSampleSizeFor(provider);
    ProviderCatalogService.ResponseStanding standing = providerCatalogService.responseStandingFor(provider);
    // standing.rank() ya es null exactamente cuando la muestra no alcanza
    // (misma fuente de verdad que responseScoreMinutes) — no duplicar el
    // umbral acá.
    Integer responseMedianMinutes = standing.rank() == null ? null : providerCatalogService.responseScoreMinutes(provider);

    return new ProviderStatsResponse(
        acceptanceRate,
        acceptedCount,
        rejectedCount,
        ratingAverage,
        ratingCount,
        completedByWeek,
        responseMedianMinutes,
        responseSampleSize,
        standing.rank(),
        standing.peers()
    );
  }

  private List<ProviderStatsResponse.WeeklyCompleted> completedByWeek(Provider provider) {
    List<Lead> leads = assignedLeadsFor(provider);
    List<Long> completedLeadIds = leads.stream()
        .filter(lead -> lead.getStatus() == LeadStatus.COMPLETED)
        .map(Lead::getId)
        .toList();

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    java.time.LocalDate currentWeekStart = now.toLocalDate().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));

    // 4 semanas, más vieja primero, para que el mini-gráfico del front no
    // tenga que reordenar.
    List<java.time.LocalDate> weekStarts = new ArrayList<>();
    for (int i = 3; i >= 0; i--) {
      weekStarts.add(currentWeekStart.minusWeeks(i));
    }
    Map<java.time.LocalDate, Integer> counts = new LinkedHashMap<>();
    for (java.time.LocalDate ws : weekStarts) counts.put(ws, 0);

    if (!completedLeadIds.isEmpty()) {
      List<LeadEvent> events = leadEventRepository.findByLeadIdInOrderByLeadIdAscCreatedAtAsc(completedLeadIds);
      // Último evento "→ COMPLETED" por lead (un lead puede completarse,
      // reabrirse y completarse de nuevo en teoría; nos quedamos con el
      // más reciente).
      Map<Long, OffsetDateTime> completedAtByLead = new LinkedHashMap<>();
      for (LeadEvent event : events) {
        if ("PROVIDER_STATUS_CHANGE".equals(event.getType()) && event.getMessage() != null
            && event.getMessage().endsWith("→ COMPLETED")) {
          completedAtByLead.put(event.getLead().getId(), event.getCreatedAt());
        }
      }
      java.time.LocalDate windowStart = weekStarts.get(0);
      for (OffsetDateTime completedAt : completedAtByLead.values()) {
        if (completedAt == null) continue;
        java.time.LocalDate completedDate = completedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        if (completedDate.isBefore(windowStart)) continue;
        java.time.LocalDate weekStart = completedDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        counts.computeIfPresent(weekStart, (k, v) -> v + 1);
      }
    }

    List<ProviderStatsResponse.WeeklyCompleted> result = new ArrayList<>();
    for (java.time.LocalDate ws : weekStarts) {
      result.add(new ProviderStatsResponse.WeeklyCompleted(ws.toString(), counts.get(ws)));
    }
    return result;
  }
}
