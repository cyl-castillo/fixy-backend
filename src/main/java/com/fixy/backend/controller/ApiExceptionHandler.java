package com.fixy.backend.controller;

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
