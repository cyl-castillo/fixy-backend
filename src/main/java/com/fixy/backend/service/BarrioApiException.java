package com.fixy.backend.service;

import org.springframework.http.HttpStatus;

/** Error de Fixy Barrio con código estable ({@code {"error":"fuera_de_zona"}}), ver ApiExceptionHandler. */
public class BarrioApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public BarrioApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static BarrioApiException fueraDeZona() {
    return new BarrioApiException(HttpStatus.UNPROCESSABLE_ENTITY, "fuera_de_zona",
        "por ahora solo Ciudad de la Costa");
  }
}
