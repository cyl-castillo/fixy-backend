package com.fixy.backend.controller;

import com.fixy.backend.dto.LeadPaymentSummary;
import com.fixy.backend.dto.MarkPaymentPaidRequest;
import com.fixy.backend.model.CommissionStatus;
import com.fixy.backend.service.LeadPaymentQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Panel ops de comisiones (P0-1 / H1.5): lista de LeadPayment con filtro
 * opcional por estado. Mismo esquema de auth que el resto de /api/ops/**
 * (ver SecurityConfig: requiere autenticación básica de ops).
 */
@RestController
@RequestMapping("/api/ops/payments")
public class OpsPaymentsController {

  private final LeadPaymentQueryService leadPaymentQueryService;

  public OpsPaymentsController(LeadPaymentQueryService leadPaymentQueryService) {
    this.leadPaymentQueryService = leadPaymentQueryService;
  }

  @GetMapping
  public List<LeadPaymentSummary> list(@RequestParam(required = false) String status) {
    CommissionStatus statusFilter = null;
    if (status != null && !status.isBlank()) {
      try {
        statusFilter = CommissionStatus.valueOf(status.toUpperCase());
      } catch (IllegalArgumentException ex) {
        throw new ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "'status' inválido: usar uno de PENDING, PAID, WAIVED, OVERDUE"
        );
      }
    }
    return leadPaymentQueryService.list(statusFilter);
  }

  /**
   * Panel admin (MVP): marca una comisión como cobrada por fuera de
   * Mercado Pago (transferencia u otro medio). Idempotente — repetir la
   * llamada sobre una comisión ya PAID no falla ni duplica el evento,
   * simplemente devuelve el estado actual (ver
   * {@link LeadPaymentQueryService#markPaidManually}).
   */
  @PatchMapping("/{id}/mark-paid")
  public LeadPaymentSummary markPaid(
      @PathVariable Long id,
      @RequestBody(required = false) MarkPaymentPaidRequest request
  ) {
    String note = request != null ? request.note() : null;
    return leadPaymentQueryService.markPaidManually(id, note);
  }
}
