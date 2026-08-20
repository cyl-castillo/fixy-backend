package com.fixy.backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
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

  public PublicLeadAbuseProtectionService(
      @Value("${fixy.abuse.max-requests-per-window:5}") int maxRequestsPerWindow,
      @Value("${fixy.abuse.window-seconds:600}") long windowSeconds,
      @Value("${fixy.abuse.offer-inquiry.max-requests-per-window:5}") int offerInquiryMaxRequestsPerWindow,
      @Value("${fixy.abuse.offer-inquiry.window-seconds:600}") long offerInquiryWindowSeconds,
      @Value("${fixy.abuse.offer-submission.max-requests-per-window:5}") int offerSubmissionMaxRequestsPerWindow,
      @Value("${fixy.abuse.offer-submission.window-seconds:600}") long offerSubmissionWindowSeconds
  ) {
    this.maxRequestsPerWindow = maxRequestsPerWindow;
    this.window = Duration.ofSeconds(windowSeconds);
    this.offerInquiryMaxRequestsPerWindow = offerInquiryMaxRequestsPerWindow;
    this.offerInquiryWindow = Duration.ofSeconds(offerInquiryWindowSeconds);
    this.offerSubmissionMaxRequestsPerWindow = offerSubmissionMaxRequestsPerWindow;
    this.offerSubmissionWindow = Duration.ofSeconds(offerSubmissionWindowSeconds);
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
