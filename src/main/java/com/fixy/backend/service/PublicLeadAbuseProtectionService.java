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

  private final int maxRequestsPerWindow;
  private final Duration window;
  private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();

  public PublicLeadAbuseProtectionService(
      @Value("${fixy.abuse.max-requests-per-window:5}") int maxRequestsPerWindow,
      @Value("${fixy.abuse.window-seconds:600}") long windowSeconds
  ) {
    this.maxRequestsPerWindow = maxRequestsPerWindow;
    this.window = Duration.ofSeconds(windowSeconds);
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
    Instant now = Instant.now();
    Instant threshold = now.minus(window);

    Deque<Instant> requests = requestsByIp.computeIfAbsent(ip, ignored -> new ArrayDeque<>());

    synchronized (requests) {
      while (!requests.isEmpty() && requests.peekFirst().isBefore(threshold)) {
        requests.pollFirst();
      }

      if (requests.size() >= maxRequestsPerWindow) {
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
            "too many public lead requests, retry later");
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
