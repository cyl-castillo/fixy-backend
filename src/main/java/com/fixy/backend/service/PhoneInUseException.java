package com.fixy.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 409 estructurado del autoregistro público de comercio ({@code
 * BusinessRegistrationService}) cuando el WhatsApp ya pertenece a OTRO
 * comercio: el contrato con el frontend pide un body {@code
 * {"code":"phone-in-use"}} distinguible de cualquier otro 409 genérico. Ver
 * {@code ApiExceptionHandler#handlePhoneInUse}, que intercepta este tipo
 * puntual antes que el handler genérico de {@link ResponseStatusException}
 * (Spring resuelve @ExceptionHandler por el tipo más específico, no importa
 * el orden de declaración).
 */
public class PhoneInUseException extends ResponseStatusException {
  public PhoneInUseException(String message) {
    super(HttpStatus.CONFLICT, message);
  }
}
