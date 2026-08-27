package com.fixy.backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicLeadAbuseProtectionService {

  private static final int MAX_CONTENT_LENGTH = 2000;
  private static final int MIN_PROBLEM_LENGTH = 12;

  /** Mini-form de consulta de oferta (FIXY_OFERTAS_CTA_DESIGN.md §4.2/§6):
   * "¿tienen talle M?" ya es una consulta completa, no hace falta el mínimo
   * de 12 caracteres que exige un problema de servicio para clasificar. */
  private static final int INQUIRY_MESSAGE_MIN_LENGTH = 5;
  private static final int INQUIRY_MESSAGE_MAX_LENGTH = 500;
  private static final int INQUIRY_FIELD_MAX_LENGTH = 100;

  /** Alta pública de ofertas (fase 2 "ofertas protagonistas"): largos sanos
   * calcados de los límites de columna reales — Business.address (V17,
   * varchar 255), Offer.description (@Column length=1000) — para que un
   * body absurdamente largo nunca llegue a intentar un INSERT. */
  private static final int SUBMISSION_NAME_MAX_LENGTH = 150;
  private static final int SUBMISSION_WHATSAPP_MAX_LENGTH = 30;
  private static final int SUBMISSION_CATEGORY_MAX_LENGTH = 60;
  private static final int SUBMISSION_ZONE_MAX_LENGTH = 100;
  private static final int SUBMISSION_ADDRESS_MAX_LENGTH = 255;
  private static final int SUBMISSION_TITLE_MAX_LENGTH = 150;
  private static final int SUBMISSION_DISCOUNT_TEXT_MAX_LENGTH = 200;
  private static final int SUBMISSION_DESCRIPTION_MAX_LENGTH = 1000;

  /** Alta pública de suscripción push (Fase Push-2, enganche): tope de ofertas guardadas por suscripción. */
  private static final int PUSH_SUBSCRIPTION_SAVED_OFFER_IDS_MAX = 50;

  /** Consulta al catálogo de la ficha (Fase 2, motor de respuesta): largos
   * calcados de las columnas reales de {@code business_inquiries} (V25). */
  private static final int BUSINESS_INQUIRY_QUESTION_MIN_LENGTH = 5;
  private static final int BUSINESS_INQUIRY_QUESTION_MAX_LENGTH = 500;
  private static final int BUSINESS_INQUIRY_VISITOR_NAME_MAX_LENGTH = 80;
  private static final int BUSINESS_INQUIRY_VISITOR_WHATSAPP_MAX_LENGTH = 30;
  private static final int BUSINESS_INQUIRY_PUSH_ENDPOINT_MAX_LENGTH = 500;

  private final int maxRequestsPerWindow;
  private final Duration window;
  private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();

  /** Ventana propia para inquiries de oferta (§6): un vecino navegando
   * ofertas no debería gastar su cupo de "puedo pedir un servicio" solo por
   * tocar "Consultar" un par de veces — mapa y config separados de leads. */
  private final int offerInquiryMaxRequestsPerWindow;
  private final Duration offerInquiryWindow;
  private final Map<String, Deque<Instant>> requestsByOfferInquiryIp = new ConcurrentHashMap<>();

  /** Ventana propia para el alta pública de ofertas: mismo criterio que
   * offer-inquiry (§6), un comerciante cargando su oferta no compite por
   * cupo con un vecino pidiendo un servicio ni con uno consultando otra. */
  private final int offerSubmissionMaxRequestsPerWindow;
  private final Duration offerSubmissionWindow;
  private final Map<String, Deque<Instant>> requestsByOfferSubmissionIp = new ConcurrentHashMap<>();

  /** Ventana propia para el alta pública de suscripción push (Fase Push-2):
   * misma familia que offer-inquiry/offer-submission — sin honeypot (es JSON
   * de la PWA, no un form con campo oculto), solo rate limit por IP. */
  private final int pushSubscriptionMaxRequestsPerWindow;
  private final Duration pushSubscriptionWindow;
  private final Map<String, Deque<Instant>> requestsByPushSubscriptionIp = new ConcurrentHashMap<>();

  /** Ventana propia y CHICA para el panel self-service del comercio (Fase 5,
   * {@code MerchantPanelService}): son acciones de dueño (leer su panel,
   * renovar/pausar una oferta), no deberían competir por cupo con las demás
   * familias públicas — y una ventana corta con un tope generoso alcanza
   * para el uso legítimo (recarga de página, un par de toques seguidos)
   * mientras sigue frenando el intento de adivinar tokens a fuerza bruta
   * (espacio de 32 bytes aleatorios, ver {@code BusinessService}). Cubre
   * lectura Y mutación del panel — el mismo pool para las tres rutas. */
  private final int merchantPanelMaxRequestsPerWindow;
  private final Duration merchantPanelWindow;
  private final Map<String, Deque<Instant>> requestsByMerchantPanelIp = new ConcurrentHashMap<>();

  /** Ventana propia para la consulta pública al catálogo de la ficha (Fase
   * 2): mismo criterio que offer-inquiry (§6 del CTA original) — un vecino
   * preguntándole a un comercio no debería competir por cupo con las demás
   * familias públicas. */
  private final int businessInquiryMaxRequestsPerWindow;
  private final Duration businessInquiryWindow;
  private final Map<String, Deque<Instant>> requestsByBusinessInquiryIp = new ConcurrentHashMap<>();

  /** Ventana propia y GENEROSA para la página pública del comercio (Fase 3,
   * {@code PublicBusinessService}): a diferencia del panel del dueño (chica,
   * a propósito, para frenar fuerza bruta de token), acá es tráfico
   * orgánico esperado — visitas reales, bots de preview, compartidos — así
   * que un tope alto con ventana corta alcanza para frenar solo scraping
   * agresivo sin afectar el uso legítimo. */
  private final int publicBusinessPageMaxRequestsPerWindow;
  private final Duration publicBusinessPageWindow;
  private final Map<String, Deque<Instant>> requestsByPublicBusinessPageIp = new ConcurrentHashMap<>();

  /** Ventana propia para el autoregistro público de PROVEEDOR (Fase 1+2
   * "puerta única de registro", 2026-08-27): hasta ahora {@code
   * ProviderRegistrationService} no tenía ningún freno de abuso — mismo tope
   * que el resto de la familia (~5/600s), agregado acá sin tocar el
   * comportamiento de validación existente del servicio. */
  private final int providerRegistrationMaxRequestsPerWindow;
  private final Duration providerRegistrationWindow;
  private final Map<String, Deque<Instant>> requestsByProviderRegistrationIp = new ConcurrentHashMap<>();

  /** Ventana propia para el autoregistro público de COMERCIO (Fase 1+2):
   * misma familia y mismo criterio que {@link #providerRegistrationMaxRequestsPerWindow}. */
  private final int businessRegistrationMaxRequestsPerWindow;
  private final Duration businessRegistrationWindow;
  private final Map<String, Deque<Instant>> requestsByBusinessRegistrationIp = new ConcurrentHashMap<>();

  public PublicLeadAbuseProtectionService(
      @Value("${fixy.abuse.max-requests-per-window:5}") int maxRequestsPerWindow,
      @Value("${fixy.abuse.window-seconds:600}") long windowSeconds,
      @Value("${fixy.abuse.offer-inquiry.max-requests-per-window:5}") int offerInquiryMaxRequestsPerWindow,
      @Value("${fixy.abuse.offer-inquiry.window-seconds:600}") long offerInquiryWindowSeconds,
      @Value("${fixy.abuse.offer-submission.max-requests-per-window:5}") int offerSubmissionMaxRequestsPerWindow,
      @Value("${fixy.abuse.offer-submission.window-seconds:600}") long offerSubmissionWindowSeconds,
      @Value("${fixy.abuse.push-subscription.max-requests-per-window:5}") int pushSubscriptionMaxRequestsPerWindow,
      @Value("${fixy.abuse.push-subscription.window-seconds:600}") long pushSubscriptionWindowSeconds,
      @Value("${fixy.abuse.merchant-panel.max-requests-per-window:20}") int merchantPanelMaxRequestsPerWindow,
      @Value("${fixy.abuse.merchant-panel.window-seconds:60}") long merchantPanelWindowSeconds,
      @Value("${fixy.abuse.business-inquiry.max-requests-per-window:5}") int businessInquiryMaxRequestsPerWindow,
      @Value("${fixy.abuse.business-inquiry.window-seconds:600}") long businessInquiryWindowSeconds,
      @Value("${fixy.abuse.public-business-page.max-requests-per-window:60}") int publicBusinessPageMaxRequestsPerWindow,
      @Value("${fixy.abuse.public-business-page.window-seconds:60}") long publicBusinessPageWindowSeconds,
      @Value("${fixy.abuse.provider-registration.max-requests-per-window:5}") int providerRegistrationMaxRequestsPerWindow,
      @Value("${fixy.abuse.provider-registration.window-seconds:600}") long providerRegistrationWindowSeconds,
      @Value("${fixy.abuse.business-registration.max-requests-per-window:5}") int businessRegistrationMaxRequestsPerWindow,
      @Value("${fixy.abuse.business-registration.window-seconds:600}") long businessRegistrationWindowSeconds
  ) {
    this.maxRequestsPerWindow = maxRequestsPerWindow;
    this.window = Duration.ofSeconds(windowSeconds);
    this.offerInquiryMaxRequestsPerWindow = offerInquiryMaxRequestsPerWindow;
    this.offerInquiryWindow = Duration.ofSeconds(offerInquiryWindowSeconds);
    this.offerSubmissionMaxRequestsPerWindow = offerSubmissionMaxRequestsPerWindow;
    this.offerSubmissionWindow = Duration.ofSeconds(offerSubmissionWindowSeconds);
    this.pushSubscriptionMaxRequestsPerWindow = pushSubscriptionMaxRequestsPerWindow;
    this.pushSubscriptionWindow = Duration.ofSeconds(pushSubscriptionWindowSeconds);
    this.merchantPanelMaxRequestsPerWindow = merchantPanelMaxRequestsPerWindow;
    this.merchantPanelWindow = Duration.ofSeconds(merchantPanelWindowSeconds);
    this.businessInquiryMaxRequestsPerWindow = businessInquiryMaxRequestsPerWindow;
    this.businessInquiryWindow = Duration.ofSeconds(businessInquiryWindowSeconds);
    this.publicBusinessPageMaxRequestsPerWindow = publicBusinessPageMaxRequestsPerWindow;
    this.publicBusinessPageWindow = Duration.ofSeconds(publicBusinessPageWindowSeconds);
    this.providerRegistrationMaxRequestsPerWindow = providerRegistrationMaxRequestsPerWindow;
    this.providerRegistrationWindow = Duration.ofSeconds(providerRegistrationWindowSeconds);
    this.businessRegistrationMaxRequestsPerWindow = businessRegistrationMaxRequestsPerWindow;
    this.businessRegistrationWindow = Duration.ofSeconds(businessRegistrationWindowSeconds);
  }

  public void validate(String clientIp, String problem) {
    validateProblem(problem);
    enforceRateLimit(normalizeIp(clientIp));
  }

  public void validateContextUpdate(String clientIp, String problem, String notes, String location) {
    if (!hasText(problem) && !hasText(notes) && !hasText(location)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "problem, notes or location must be provided");
    }

    if (hasText(problem)) {
      validateProblem(problem);
    }

    if (hasText(notes) && notes.trim().length() > MAX_CONTENT_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "notes exceeds max length");
    }

    if (hasText(location) && location.trim().length() > 300) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "location exceeds max length");
    }

    enforceRateLimit(normalizeIp(clientIp));
  }

  /**
   * Mini-form de consulta de oferta (ruta comercio, §4.2/§6): longitud de
   * los tres campos + rate limit por IP con ventana propia (no comparte
   * cupo con {@code /api/public/leads}). El honeypot ({@code website} no
   * vacío) se valida en el caller ({@code OfferInquiryService}), no acá —
   * ese caso nunca debe llegar a un error, siempre 201 sin persistir.
   */
  public void validateOfferInquiry(String clientIp, String name, String whatsappNumber, String message) {
    String trimmedMessage = message == null ? "" : message.trim();
    if (trimmedMessage.length() < INQUIRY_MESSAGE_MIN_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "message must be at least %d characters".formatted(INQUIRY_MESSAGE_MIN_LENGTH));
    }
    if (trimmedMessage.length() > INQUIRY_MESSAGE_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message exceeds max length");
    }
    if (!hasText(name) || name.trim().length() > INQUIRY_FIELD_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "name is required and must be at most %d characters".formatted(INQUIRY_FIELD_MAX_LENGTH));
    }
    if (!hasText(whatsappNumber) || whatsappNumber.trim().length() > INQUIRY_FIELD_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "whatsappNumber is required and must be at most %d characters".formatted(INQUIRY_FIELD_MAX_LENGTH));
    }
    enforceRateLimit(requestsByOfferInquiryIp, normalizeIp(clientIp), offerInquiryMaxRequestsPerWindow, offerInquiryWindow);
  }

  /**
   * Alta pública de ofertas (fase 2 "ofertas protagonistas", puerta del
   * comerciante): longitud de los campos obligatorios + rate limit por IP
   * con ventana propia. El honeypot ({@code website} no vacío) se valida en
   * el caller ({@code PublicOfferSubmissionService}), no acá — mismo
   * contrato que {@link #validateOfferInquiry}: ese caso nunca debe llegar a
   * un error, siempre 201 sin persistir.
   */
  public void validateOfferSubmission(
      String clientIp, String businessName, String whatsappNumber, String category,
      String zone, String title, String address, String discountText, String description
  ) {
    if (!hasText(businessName) || businessName.trim().length() > SUBMISSION_NAME_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "businessName is required and must be at most %d characters".formatted(SUBMISSION_NAME_MAX_LENGTH));
    }
    if (!hasText(whatsappNumber) || whatsappNumber.trim().length() > SUBMISSION_WHATSAPP_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "whatsappNumber is required and must be at most %d characters".formatted(SUBMISSION_WHATSAPP_MAX_LENGTH));
    }
    if (!hasText(category) || category.trim().length() > SUBMISSION_CATEGORY_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "category is required and must be at most %d characters".formatted(SUBMISSION_CATEGORY_MAX_LENGTH));
    }
    if (!hasText(zone) || zone.trim().length() > SUBMISSION_ZONE_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "zone is required and must be at most %d characters".formatted(SUBMISSION_ZONE_MAX_LENGTH));
    }
    if (!hasText(title) || title.trim().length() > SUBMISSION_TITLE_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "title is required and must be at most %d characters".formatted(SUBMISSION_TITLE_MAX_LENGTH));
    }
    if (hasText(address) && address.trim().length() > SUBMISSION_ADDRESS_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "address exceeds max length");
    }
    if (hasText(discountText) && discountText.trim().length() > SUBMISSION_DISCOUNT_TEXT_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "discountText exceeds max length");
    }
    if (hasText(description) && description.trim().length() > SUBMISSION_DESCRIPTION_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "description exceeds max length");
    }
    enforceRateLimit(requestsByOfferSubmissionIp, normalizeIp(clientIp), offerSubmissionMaxRequestsPerWindow, offerSubmissionWindow);
  }

  /**
   * Alta pública de suscripción push (Fase Push-2, enganche): sin honeypot
   * (el caller no expone un form, es JSON directo de la PWA) — solo tope de
   * {@code savedOfferIds} y rate limit por IP con ventana propia.
   */
  public void validatePushSubscription(String clientIp, List<Long> savedOfferIds) {
    if (savedOfferIds != null && savedOfferIds.size() > PUSH_SUBSCRIPTION_SAVED_OFFER_IDS_MAX) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "savedOfferIds must have at most %d items".formatted(PUSH_SUBSCRIPTION_SAVED_OFFER_IDS_MAX));
    }
    enforceRateLimit(requestsByPushSubscriptionIp, normalizeIp(clientIp), pushSubscriptionMaxRequestsPerWindow, pushSubscriptionWindow);
  }

  /**
   * Panel self-service del comercio (Fase 5): rate limit por IP, ventana
   * propia y chica (ver javadoc del campo). Sin validación de contenido acá
   * — el token en sí (404 opaco si no resuelve) y los datos de negocio
   * (p.ej. {@code weeks} de renew) los valida {@code MerchantPanelService}.
   */
  public void validateMerchantPanel(String clientIp) {
    enforceRateLimit(requestsByMerchantPanelIp, normalizeIp(clientIp), merchantPanelMaxRequestsPerWindow, merchantPanelWindow);
  }

  /**
   * Consulta pública al catálogo de la ficha (Fase 2, motor de respuesta):
   * {@code question} obligatoria 5-500 (igual criterio que {@link
   * #validateOfferInquiry}), el resto opcional pero acotado a la longitud
   * real de columna de {@code business_inquiries} (V25). El honeypot ({@code
   * website} no vacío) se valida en el caller ({@code
   * BusinessInquiryService.create}), no acá — nunca debe llegar a un error.
   */
  public void validateBusinessInquiry(
      String clientIp, String question, String visitorName, String visitorWhatsapp, String pushEndpoint
  ) {
    String trimmedQuestion = question == null ? "" : question.trim();
    if (trimmedQuestion.length() < BUSINESS_INQUIRY_QUESTION_MIN_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "question must be at least %d characters".formatted(BUSINESS_INQUIRY_QUESTION_MIN_LENGTH));
    }
    if (trimmedQuestion.length() > BUSINESS_INQUIRY_QUESTION_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question exceeds max length");
    }
    if (hasText(visitorName) && visitorName.trim().length() > BUSINESS_INQUIRY_VISITOR_NAME_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visitorName exceeds max length");
    }
    if (hasText(visitorWhatsapp) && visitorWhatsapp.trim().length() > BUSINESS_INQUIRY_VISITOR_WHATSAPP_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visitorWhatsapp exceeds max length");
    }
    if (hasText(pushEndpoint) && pushEndpoint.trim().length() > BUSINESS_INQUIRY_PUSH_ENDPOINT_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pushEndpoint exceeds max length");
    }
    enforceRateLimit(requestsByBusinessInquiryIp, normalizeIp(clientIp), businessInquiryMaxRequestsPerWindow, businessInquiryWindow);
  }

  /**
   * Adjuntar {@code pushEndpoint} tardío a una consulta ya creada (Fase 2,
   * hueco de contrato): mismo pool que {@link #validateBusinessInquiry} —
   * es la misma familia de acciones del vecino sobre su propia consulta,
   * no amerita una ventana separada.
   */
  public void validateBusinessInquiryPushUpdate(String clientIp, String pushEndpoint) {
    if (hasText(pushEndpoint) && pushEndpoint.trim().length() > BUSINESS_INQUIRY_PUSH_ENDPOINT_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pushEndpoint exceeds max length");
    }
    enforceRateLimit(requestsByBusinessInquiryIp, normalizeIp(clientIp), businessInquiryMaxRequestsPerWindow, businessInquiryWindow);
  }

  /**
   * Página pública del comercio (Fase 3): sin validación de contenido (es
   * un GET, no hay body) — solo rate limit por IP con ventana propia y
   * generosa (ver javadoc del campo).
   */
  public void validatePublicBusinessPage(String clientIp) {
    enforceRateLimit(requestsByPublicBusinessPageIp, normalizeIp(clientIp),
        publicBusinessPageMaxRequestsPerWindow, publicBusinessPageWindow);
  }

  /**
   * Autoregistro público de PROVEEDOR (Fase 1+2 "puerta única de registro"):
   * solo rate limit por IP con ventana propia — la validación de contenido
   * (nombre, teléfono, categorías) sigue viviendo en
   * {@code ProviderRegistrationService}, igual que antes de este freno.
   */
  public void validateProviderRegistration(String clientIp) {
    enforceRateLimit(requestsByProviderRegistrationIp, normalizeIp(clientIp),
        providerRegistrationMaxRequestsPerWindow, providerRegistrationWindow);
  }

  /**
   * Autoregistro público de COMERCIO (Fase 1+2): mismo criterio que {@link
   * #validateProviderRegistration} — solo rate limit, la validación de
   * contenido vive en {@code BusinessRegistrationService}.
   */
  public void validateBusinessRegistration(String clientIp) {
    enforceRateLimit(requestsByBusinessRegistrationIp, normalizeIp(clientIp),
        businessRegistrationMaxRequestsPerWindow, businessRegistrationWindow);
  }

  private void validateProblem(String problem) {
    String value = problem == null ? "" : problem.trim();

    if (value.length() < MIN_PROBLEM_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "problem must be at least %d characters".formatted(MIN_PROBLEM_LENGTH));
    }

    if (value.length() > MAX_CONTENT_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "problem exceeds max length");
    }
  }

  private void enforceRateLimit(String ip) {
    enforceRateLimit(requestsByIp, ip, maxRequestsPerWindow, window);
  }

  private void enforceRateLimit(Map<String, Deque<Instant>> store, String ip, int maxRequests, Duration windowDuration) {
    Instant now = Instant.now();
    Instant threshold = now.minus(windowDuration);

    Deque<Instant> requests = store.computeIfAbsent(ip, ignored -> new ArrayDeque<>());

    synchronized (requests) {
      while (!requests.isEmpty() && requests.peekFirst().isBefore(threshold)) {
        requests.pollFirst();
      }

      if (requests.size() >= maxRequests) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
            "too many requests, retry later");
      }

      requests.addLast(now);
    }
  }

  private String normalizeIp(String clientIp) {
    if (clientIp == null || clientIp.isBlank()) {
      return "unknown";
    }
    return clientIp.trim();
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isBlank();
  }
}
