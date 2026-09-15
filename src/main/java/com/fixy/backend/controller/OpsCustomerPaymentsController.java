package com.fixy.backend.controller;

import com.fixy.backend.dto.CustomerPaymentSummary;
import com.fixy.backend.dto.MarkPaymentPaidRequest;
import com.fixy.backend.model.CustomerPaymentStatus;
import com.fixy.backend.service.CustomerPaymentQueryService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Panel ops "Cobros" (contrato §A.4.6): cargos de servicio al CLIENTE (no
 * comisiones al técnico, ver {@link OpsPaymentsController} para eso). Mismo
 * esquema de auth que el resto de /api/ops/** (SecurityConfig: básica de ops).
 */
@RestController
@RequestMapping("/api/ops/customer-payments")
public class OpsCustomerPaymentsController {

  private final CustomerPaymentQueryService customerPaymentQueryService;

  public OpsCustomerPaymentsController(CustomerPaymentQueryService customerPaymentQueryService) {
    this.customerPaymentQueryService = customerPaymentQueryService;
  }

  @GetMapping
  public List<CustomerPaymentSummary> list(@RequestParam(required = false) String status) {
    CustomerPaymentStatus statusFilter = null;
    if (status != null && !status.isBlank()) {
      try {
        statusFilter = CustomerPaymentStatus.valueOf(status.toUpperCase());
      } catch (IllegalArgumentException ex) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "'status' inválido: usar uno de PENDING, PAID, WAIVED");
      }
    }
    return customerPaymentQueryService.list(statusFilter);
  }

  @PatchMapping("/{id}/mark-paid")
  public CustomerPaymentSummary markPaid(
      @PathVariable Long id,
      @RequestBody(required = false) MarkPaymentPaidRequest request
  ) {
    String note = request != null ? request.note() : null;
    return customerPaymentQueryService.markPaidManually(id, note);
  }

  @PatchMapping("/{id}/waive")
  public CustomerPaymentSummary waive(
      @PathVariable Long id,
      @RequestBody(required = false) MarkPaymentPaidRequest request
  ) {
    String note = request != null ? request.note() : null;
    return customerPaymentQueryService.waiveManually(id, note);
  }
}
