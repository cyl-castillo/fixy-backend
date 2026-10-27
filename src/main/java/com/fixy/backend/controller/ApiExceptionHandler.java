package com.fixy.backend.controller;

import com.fixy.backend.service.BarrioApiException;
import com.fixy.backend.service.PhoneInUseException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

  /**
   * Autoregistro público de comercio con WhatsApp ya en uso (Fase 1+2
   * "puerta única de registro"): el contrato con el frontend pide un body
   * PLANO {@code {"code":"phone-in-use"}}, distinto del sobre genérico
   * {@code {"error":{...}}} que arma {@link #handleResponseStatus} para
   * cualquier otro {@link ResponseStatusException} — Spring resuelve este
   * handler primero por ser el tipo más específico (no importa el orden de
   * declaración de los métodos).
   */
  @ExceptionHandler(PhoneInUseException.class)
  public ResponseEntity<Map<String, Object>> handlePhoneInUse(PhoneInUseException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "phone-in-use"));
  }

  /** Fixy Barrio (B1): código estable en body plano, {@code {"error":"fuera_de_zona"}} con 422. */
  @ExceptionHandler(BarrioApiException.class)
  public ResponseEntity<Map<String, Object>> handleBarrio(BarrioApiException exception) {
    return ResponseEntity.status(exception.status())
        .body(Map.of("error", exception.code(), "message", exception.getMessage()));
  }

  /** Foto más grande que el límite multipart: 413 con el mismo sobre que el resto de la API. */
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, Object>> handleTooLarge(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(buildError(
        "payload_too_large", "archivo demasiado grande (max 8 MB)", null, false, request.getRequestURI()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(
      MethodArgumentNotValidException exception,
      HttpServletRequest request
  ) {
    Map<String, String> errors = new HashMap<>();

    for (FieldError error : exception.getBindingResult().getFieldErrors()) {
      errors.put(error.getField(), error.getDefaultMessage());
    }

    return ResponseEntity.badRequest().body(buildError(
        "validation_error",
        "request validation failed",
        errors,
        false,
        request.getRequestURI()
    ));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handleResponseStatus(
      ResponseStatusException exception,
      HttpServletRequest request
  ) {
    String code = exception.getStatusCode().value() == 429 ? "rate_limited" : "request_failed";
    boolean retryable = exception.getStatusCode().value() == 429;

    return ResponseEntity.status(exception.getStatusCode()).body(buildError(
        code,
        exception.getReason() == null ? "request failed" : exception.getReason(),
        null,
        retryable,
        request.getRequestURI()
    ));
  }

  private Map<String, Object> buildError(
      String code,
      String message,
      Object details,
      boolean retryable,
      String path
  ) {
    Map<String, Object> error = new HashMap<>();
    error.put("code", code);
    error.put("message", message);
    error.put("retryable", retryable);
    error.put("path", path);
    error.put("timestamp", OffsetDateTime.now().toString());
    if (details != null) {
      error.put("details", details);
    }

    return Map.of("error", error);
  }
}
