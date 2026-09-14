package com.fixy.backend.controller;

import com.fixy.backend.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refundación de Fixy, fase 1 (contrato §7): la UI estática legacy
 * ({@code index.html}/{@code ops.html} y compañía) se retiró — "/" pasa a
 * responder JSON simple para que ningún monitor externo (ni humano
 * abriendo la IP en el navegador) se encuentre con un 404. El detalle real
 * de salud vive en {@code /api/health} (ver {@link HealthController} y
 * {@code scripts/monitor_fixy.sh}).
 */
@RestController
public class RootController {

  @GetMapping("/")
  public HealthResponse root() {
    return new HealthResponse("ok", "fixy-backend");
  }
}
