package com.fixy.backend.dto;

import java.math.BigDecimal;

/**
 * Resumen de comisiones del proveedor para su panel self-service (H_B).
 * Montos agregados (no fila por fila, eso queda para ops); si el proveedor
 * no tiene comisiones todavía (payments deshabilitado o sin leads
 * COMPLETED), pendingAmount/paidAmount vienen en BigDecimal.ZERO y los
 * counts en 0 — el front debe mostrar un empty state prolijo, no un error.
 *
 * earningsThisMonth/earningsTotal (Ola 1 #2 del plan de superapp del
 * proveedor): suma de {@code amountCharged} — lo que el proveedor GANÓ,
 * no lo que le debe a Fixy. "Este mes" se calcula sobre
 * {@link com.fixy.backend.model.LeadPayment#getCreatedAt()} (el LeadPayment
 * se crea en el momento exacto en que el proveedor marca COMPLETED, así que
 * su createdAt es la fecha real de finalización del trabajo).
 */
public record ProviderCommissionSummary(
    BigDecimal pendingAmount,
    int pendingCount,
    BigDecimal paidAmount,
    int paidCount,
    String currency,
    BigDecimal earningsThisMonth,
    int earningsThisMonthCount,
    BigDecimal earningsTotal,
    int earningsTotalCount
) {
  public static ProviderCommissionSummary empty() {
    return new ProviderCommissionSummary(
        BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, "UYU", BigDecimal.ZERO, 0, BigDecimal.ZERO, 0);
  }
}
