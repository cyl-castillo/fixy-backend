package com.fixy.backend.dto;

import java.math.BigDecimal;

/**
 * Resumen de comisiones del proveedor para su panel self-service (H_B).
 * Montos agregados (no fila por fila, eso queda para ops); si el proveedor
 * no tiene comisiones todavía (payments deshabilitado o sin leads
 * COMPLETED), pendingAmount/paidAmount vienen en BigDecimal.ZERO y los
 * counts en 0 — el front debe mostrar un empty state prolijo, no un error.
 */
public record ProviderCommissionSummary(
    BigDecimal pendingAmount,
    int pendingCount,
    BigDecimal paidAmount,
    int paidCount,
    String currency
) {
  public static ProviderCommissionSummary empty() {
    return new ProviderCommissionSummary(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, "UYU");
  }
}
