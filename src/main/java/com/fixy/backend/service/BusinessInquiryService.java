package com.fixy.backend.service;

import com.fixy.backend.dto.BusinessInquiryCreateRequest;
import com.fixy.backend.dto.BusinessInquiryCreateResponse;
import com.fixy.backend.dto.BusinessInquiryOwnerAnswerResponse;
import com.fixy.backend.dto.BusinessInquiryPendingSummary;
import com.fixy.backend.dto.BusinessInquiryVisitorResponse;
import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessCatalogItem;
import com.fixy.backend.model.BusinessCatalogItemConfidence;
import com.fixy.backend.model.BusinessCatalogItemKind;
import com.fixy.backend.model.BusinessInquiry;
import com.fixy.backend.model.BusinessInquiryStatus;
import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.BusinessCatalogItemRepository;
import com.fixy.backend.repository.BusinessInquiryRepository;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.PushSubscriptionRepository;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Motor de respuesta con escalado al dueño (Fase 2 del gap analysis
 * 2026-08-25 §2): la pregunta de un vecino contra el catálogo de la ficha
 * ({@link CatalogAnswerService}) se responde sola con confianza suficiente;
 * si no, escala al dueño por web push al panel + Telegram (canal WhatsApp
 * bloqueado por la WABA restringida, ver gap analysis §riesgos.1), y la
 * respuesta del dueño upsertea el catálogo como {@code CONFIRMADO} — cada
 * consulta resuelta hace la ficha más precisa para la próxima.
 */
@Service
public class BusinessInquiryService {

  private static final Logger log = LoggerFactory.getLogger(BusinessInquiryService.class);

  /** Mismo orden de magnitud que {@code Business.panelToken}: 32 bytes → 43 chars base64url. */
  private static final int ACCESS_TOKEN_BYTES = 32;

  private final BusinessInquiryRepository businessInquiryRepository;
  private final BusinessRepository businessRepository;
  private final BusinessCatalogItemRepository catalogItemRepository;
  private final PushSubscriptionRepository pushSubscriptionRepository;
  private final CatalogAnswerService catalogAnswerService;
  private final BusinessTimelineService businessTimelineService;
  private final BusinessService businessService;
  private final PushNotificationService pushNotificationService;
  private final TelegramNotifyService telegramNotifyService;
  private final PublicLeadAbuseProtectionService abuseProtectionService;
  private final SecureRandom random = new SecureRandom();

  public BusinessInquiryService(
      BusinessInquiryRepository businessInquiryRepository,
      BusinessRepository businessRepository,
      BusinessCatalogItemRepository catalogItemRepository,
      PushSubscriptionRepository pushSubscriptionRepository,
      CatalogAnswerService catalogAnswerService,
      BusinessTimelineService businessTimelineService,
      BusinessService businessService,
      PushNotificationService pushNotificationService,
      TelegramNotifyService telegramNotifyService,
      PublicLeadAbuseProtectionService abuseProtectionService
  ) {
    this.businessInquiryRepository = businessInquiryRepository;
    this.businessRepository = businessRepository;
    this.catalogItemRepository = catalogItemRepository;
    this.pushSubscriptionRepository = pushSubscriptionRepository;
    this.catalogAnswerService = catalogAnswerService;
    this.businessTimelineService = businessTimelineService;
    this.businessService = businessService;
    this.pushNotificationService = pushNotificationService;
    this.telegramNotifyService = telegramNotifyService;
    this.abuseProtectionService = abuseProtectionService;
  }

  /**
   * Crea la consulta pública. El honeypot se chequea PRIMERO, antes de
   * cualquier otra validación — incluida la del {@code Business} — mismo
   * criterio que {@code OfferInquiryService.create}: un bot que completa
   * {@code website} recibe éxito igual sin filtrar ni siquiera si el
   * comercio existe.
   */
  @Transactional
  public BusinessInquiryCreateResponse create(Long businessId, BusinessInquiryCreateRequest request, String clientIp) {
    if (hasText(request.website())) {
      return BusinessInquiryCreateResponse.fakeOk();
    }
    Business business = businessRepository.findById(businessId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "business not found"));
    abuseProtectionService.validateBusinessInquiry(
        clientIp, request.question(), request.visitorName(), request.visitorWhatsapp(), request.pushEndpoint());

    CatalogAnswerService.AnswerResult result = catalogAnswerService.answer(businessId, request.question());

    BusinessInquiry inquiry = new BusinessInquiry();
    inquiry.setBusinessId(businessId);
    inquiry.setOfferId(request.offerId());
    inquiry.setQuestion(request.question().trim());
    inquiry.setVisitorName(trimToNull(request.visitorName()));
    inquiry.setVisitorWhatsapp(trimToNull(request.visitorWhatsapp()));
    inquiry.setPushEndpoint(trimToNull(request.pushEndpoint()));
    inquiry.setAccessToken(newAccessToken());

    if (result.kind() == CatalogAnswerService.AnswerResult.Kind.ESCALATE) {
      return escalate(business, inquiry);
    }
    return answerAuto(business, inquiry, result);
  }

  private BusinessInquiryCreateResponse answerAuto(
      Business business, BusinessInquiry inquiry, CatalogAnswerService.AnswerResult result
  ) {
    BusinessCatalogItem item = result.matchedItem();
    OffsetDateTime now = OffsetDateTime.now();
    inquiry.setStatus(BusinessInquiryStatus.ANSWERED_AUTO);
    inquiry.setAnswer(result.kind() == CatalogAnswerService.AnswerResult.Kind.AUTO_YES
        ? BusinessInquiry.ANSWER_SI : BusinessInquiry.ANSWER_NO);
    inquiry.setAnswerSource(BusinessInquiry.SOURCE_CATALOG);
    inquiry.setCatalogItemId(item.getId());
    inquiry.setAnsweredAt(now);
    BusinessInquiry saved = businessInquiryRepository.save(inquiry);

    businessTimelineService.appendEvent(business.getId(), "INQUIRY_AUTO_ANSWERED", "system",
        saved.getQuestion() + " → " + saved.getAnswer());

    return new BusinessInquiryCreateResponse(
        saved.getStatus().name(),
        saved.getId(),
        null,
        new BusinessInquiryCreateResponse.AnswerPayload(
            saved.getAnswer(), null, item.getPriceFrom(), item.getVerifiedAt(), business.getName())
    );
  }

  private BusinessInquiryCreateResponse escalate(Business business, BusinessInquiry inquiry) {
    inquiry.setStatus(BusinessInquiryStatus.ESCALATED);
    BusinessInquiry saved = businessInquiryRepository.save(inquiry);

    businessTimelineService.appendEvent(business.getId(), "INQUIRY_ESCALATED", "system", saved.getQuestion());
    notifyEscalation(business, saved);

    return new BusinessInquiryCreateResponse(saved.getStatus().name(), saved.getId(), saved.getAccessToken(), null);
  }

  /** Web push a TODAS las subs del comercio + Telegram siempre a ops (patrón {@code OfferInquiryService}). */
  private void notifyEscalation(Business business, BusinessInquiry inquiry) {
    businessService.ensurePanelLink(business.getId());
    Business withToken = businessRepository.findById(business.getId()).orElse(business);

    List<PushSubscription> subs = pushSubscriptionRepository.findByBusinessId(business.getId());
    if (!subs.isEmpty()) {
      String title = "Un vecino pregunta: \"%s\"".formatted(truncate(inquiry.getQuestion(), 60));
      String body = "Contestale desde tu panel — le llega al toque.";
      String url = "/mi-comercio/%s?inquiry=%d".formatted(withToken.getPanelToken(), inquiry.getId());
      for (PushSubscription sub : subs) {
        pushNotificationService.notifySubscription(sub, title, body, url);
      }
    }

    try {
      telegramNotifyService.notifyBusinessInquiryEscalated(withToken, inquiry);
    } catch (Exception ex) {
      log.warn("telegram notify business-inquiry-escalated id={} failed: {}", inquiry.getId(), ex.getMessage());
    }

    inquiry.setOwnerNotifiedAt(OffsetDateTime.now());
    businessInquiryRepository.save(inquiry);
  }

  /**
   * El dueño contesta desde su panel (sin auth, por token — patrón {@code
   * MerchantPanelService}). 409 si la consulta ya no está {@code ESCALATED}
   * (ya la contestaron, o venció). Upsertea el catálogo con la respuesta
   * (ver {@link #upsertCatalogItem}) y avisa al vecino por push si dejó su
   * endpoint.
   */
  @Transactional
  public BusinessInquiryOwnerAnswerResponse answerAsOwner(
      String clientIp, String panelToken, Long inquiryId, String answerRaw, Integer priceFrom, String note
  ) {
    abuseProtectionService.validateMerchantPanel(clientIp);
    Business business = requireBusiness(panelToken);
    BusinessInquiry inquiry = businessInquiryRepository.findByIdAndBusinessId(inquiryId, business.getId())
        .orElseThrow(this::opaqueNotFound);
    if (inquiry.getStatus() != BusinessInquiryStatus.ESCALATED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "inquiry already answered");
    }
    String answer = normalizeAnswer(answerRaw);
    boolean available = BusinessInquiry.ANSWER_SI.equals(answer);
    Integer validatedPrice = validatePriceFrom(priceFrom);

    BusinessCatalogItem item = upsertCatalogItem(business, inquiry.getQuestion(), available, validatedPrice);

    inquiry.setStatus(BusinessInquiryStatus.ANSWERED_OWNER);
    inquiry.setAnswer(answer);
    inquiry.setAnswerSource(BusinessInquiry.SOURCE_OWNER);
    inquiry.setAnswerNote(trimToNull(note));
    inquiry.setCatalogItemId(item.getId());
    inquiry.setAnsweredAt(OffsetDateTime.now());
    BusinessInquiry saved = businessInquiryRepository.save(inquiry);

    businessTimelineService.appendEvent(business.getId(), "INQUIRY_OWNER_ANSWERED", "owner",
        saved.getQuestion() + " → " + saved.getAnswer());
    notifyVisitor(business, saved);

    return new BusinessInquiryOwnerAnswerResponse(
        saved.getId(), saved.getStatus().name(), saved.getAnswer(), saved.getAnswerNote(), item.getId());
  }

  /**
   * Busca (vía {@link CatalogAnswerService#findMatch}) un ítem ACTIVO cuyo
   * label matchee la pregunta; si existe lo actualiza, si no crea uno nuevo
   * ({@code kind=PRODUCT}, label extraído de la pregunta). Siempre queda
   * {@code CONFIRMADO} — {@code verifiedAt} se estampa solo en la
   * transición hacia CONFIRMADO, mismo criterio que {@code
   * BusinessCatalogItemService}.
   */
  private BusinessCatalogItem upsertCatalogItem(Business business, String question, boolean available, Integer priceFrom) {
    Optional<BusinessCatalogItem> existing = catalogAnswerService.findMatch(business.getId(), question);
    BusinessCatalogItem item;
    boolean wasConfirmed;
    if (existing.isPresent()) {
      item = existing.get();
      wasConfirmed = item.getConfidence() == BusinessCatalogItemConfidence.CONFIRMADO;
    } else {
      item = new BusinessCatalogItem();
      item.setBusiness(business);
      item.setKind(BusinessCatalogItemKind.PRODUCT);
      item.setLabel(catalogAnswerService.extractTerm(question));
      wasConfirmed = false;
    }
    item.setAvailable(available);
    item.setConfidence(BusinessCatalogItemConfidence.CONFIRMADO);
    if (!wasConfirmed) {
      item.setVerifiedAt(OffsetDateTime.now());
    }
    if (priceFrom != null) {
      item.setPriceFrom(priceFrom);
    }
    item.setActive(true);
    BusinessCatalogItem saved = catalogItemRepository.save(item);
    businessTimelineService.appendEvent(business.getId(), "CATALOG_ITEM_CONFIRMED_BY_INQUIRY", "owner",
        saved.getLabel() + " (available=" + saved.isAvailable() + ")");
    return saved;
  }

  /** Push al vecino si dejó su endpoint (Fase Push-2) — busca la sub existente por endpoint, no-op si no hay ninguna. */
  private void notifyVisitor(Business business, BusinessInquiry inquiry) {
    if (inquiry.getPushEndpoint() == null || inquiry.getPushEndpoint().isBlank()) {
      return;
    }
    List<PushSubscription> subs = pushSubscriptionRepository.findAllByEndpointOrderByCreatedAtDesc(inquiry.getPushEndpoint());
    if (subs.isEmpty()) {
      return;
    }
    String title = "%s te respondió".formatted(business.getName());
    String body = BusinessInquiry.ANSWER_SI.equals(inquiry.getAnswer())
        ? "Sí, tienen lo que preguntaste."
        : "No, por ahora no tienen eso.";
    String url = "/consulta/%d?t=%s".formatted(inquiry.getId(), inquiry.getAccessToken());
    pushNotificationService.notifySubscription(subs.get(0), title, body, url);
  }

  /** {@code GET /api/public/inquiries/{id}?token=} — 404 opaco SIEMPRE que el token no matchea, patrón {@code MerchantPanelService}. */
  public BusinessInquiryVisitorResponse getForVisitor(Long inquiryId, String accessToken) {
    if (accessToken == null || accessToken.isBlank()) {
      throw opaqueNotFound();
    }
    BusinessInquiry inquiry = businessInquiryRepository.findByIdAndAccessToken(inquiryId, accessToken)
        .orElseThrow(this::opaqueNotFound);
    Business business = businessRepository.findById(inquiry.getBusinessId()).orElse(null);
    BusinessCatalogItem item = inquiry.getCatalogItemId() == null
        ? null
        : catalogItemRepository.findById(inquiry.getCatalogItemId()).orElse(null);

    return new BusinessInquiryVisitorResponse(
        inquiry.getId(),
        inquiry.getStatus().name(),
        inquiry.getQuestion(),
        inquiry.getAnswer(),
        inquiry.getAnswerNote(),
        item == null ? null : item.getPriceFrom(),
        item == null ? null : item.getVerifiedAt(),
        business == null ? null : business.getName()
    );
  }

  /**
   * Adjunta un {@code pushEndpoint} tardío (Fase 2, hueco de contrato): el
   * vecino activó las notificaciones DESPUÉS de crear su consulta, el POST
   * original quedó sin endpoint. Solo mientras siga {@code ESCALATED} — una
   * vez contestada (o vencida) ya no hay a quién avisarle distinto de lo
   * que ya pasó. 404 opaco si el token no matchea, mismo criterio que
   * {@link #getForVisitor}.
   */
  @Transactional
  public void attachPushEndpoint(Long inquiryId, String accessToken, String clientIp, String pushEndpoint) {
    abuseProtectionService.validateBusinessInquiryPushUpdate(clientIp, pushEndpoint);
    if (accessToken == null || accessToken.isBlank()) {
      throw opaqueNotFound();
    }
    BusinessInquiry inquiry = businessInquiryRepository.findByIdAndAccessToken(inquiryId, accessToken)
        .orElseThrow(this::opaqueNotFound);
    if (inquiry.getStatus() != BusinessInquiryStatus.ESCALATED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "inquiry no longer escalated");
    }
    inquiry.setPushEndpoint(trimToNull(pushEndpoint));
    businessInquiryRepository.save(inquiry);
  }

  /** Pendientes del panel del comercio (Fase 5 lo consume vía {@code MerchantPanelService}). */
  public List<BusinessInquiryPendingSummary> listPendingForBusiness(Long businessId) {
    return businessInquiryRepository
        .findByBusinessIdAndStatusOrderByCreatedAtDesc(businessId, BusinessInquiryStatus.ESCALATED)
        .stream()
        .map(inquiry -> new BusinessInquiryPendingSummary(
            inquiry.getId(), inquiry.getQuestion(), inquiry.getVisitorName(),
            inquiry.getVisitorWhatsapp(), inquiry.getOfferId(), inquiry.getCreatedAt()))
        .toList();
  }

  private String normalizeAnswer(String raw) {
    String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (!BusinessInquiry.ANSWER_SI.equals(value) && !BusinessInquiry.ANSWER_NO.equals(value)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer must be SI or NO");
    }
    return value;
  }

  private Integer validatePriceFrom(Integer priceFrom) {
    if (priceFrom != null && priceFrom < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "priceFrom must be >= 0");
    }
    return priceFrom;
  }

  private Business requireBusiness(String token) {
    if (token == null || token.isBlank()) {
      throw opaqueNotFound();
    }
    return businessRepository.findByPanelToken(token).orElseThrow(this::opaqueNotFound);
  }

  private ResponseStatusException opaqueNotFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
  }

  private String newAccessToken() {
    byte[] buf = new byte[ACCESS_TOKEN_BYTES];
    random.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  private String truncate(String value, int maxLen) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen).trim();
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }
}
