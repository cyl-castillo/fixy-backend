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

  private final int maxRequestsPerWindow;
  private final Duration window;
  private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();

  /** Ventana propia para inquiries de oferta (§6): un vecino navegando
   * ofertas no debería gastar su cupo de "puedo pedir un servicio" solo por
   * tocar "Consultar" un par de veces — mapa y config separados de leads. */
  private final int offerInquiryMaxRequestsPerWindow;
  private final Duration offerInquiryWindow;
  private final Map<String, Deque<Instant>> requestsByOfferInquiryIp = new ConcurrentHashMap<>();

  public PublicLeadAbuseProtectionService(
      @Value("${fixy.abuse.max-requests-per-window:5}") int maxRequestsPerWindow,
      @Value("${fixy.abuse.window-seconds:600}") long windowSeconds,
      @Value("${fixy.abuse.offer-inquiry.max-requests-per-window:5}") int offerInquiryMaxRequestsPerWindow,
      @Value("${fixy.abuse.offer-inquiry.window-seconds:600}") long offerInquiryWindowSeconds
  ) {
    this.maxRequestsPerWindow = maxRequestsPerWindow;
    this.window = Duration.ofSeconds(windowSeconds);
    this.offerInquiryMaxRequestsPerWindow = offerInquiryMaxRequestsPerWindow;
    this.offerInquiryWindow = Duration.ofSeconds(offerInquiryWindowSeconds);
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
