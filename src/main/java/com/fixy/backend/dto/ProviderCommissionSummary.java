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
    int earningsTotalCount,
    java.util.List<PendingCommission> pendingItems
) {
  /**
   * Comisión pendiente con su link de pago, para que el panel tenga un botón
   * "Pagar" real. Hallazgo del primer cobro real (lead #105, 2026-07-16):
   * el link de MP solo existía en un mensaje del chat compartido — el
   * proveedor no tenía NINGUNA forma de pagar desde su panel.
   *
   * {@code overdue} (Comisión vencida pausa el matching, FIXY_COBRANZAS.md):
   * true si esta comisión concreta es la que tiene pausado el matching del
   * proveedor — el panel la resalta con el banner ámbar en vez del resumen
   * normal.
   */
  public record PendingCommission(
      Long leadId,
      BigDecimal commissionAmount,
      String currency,
      String mpPaymentLink,
      java.time.OffsetDateTime createdAt,
      boolean overdue
  ) {
  }

  public static ProviderCommissionSummary empty() {
    return new ProviderCommissionSummary(
        BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, "UYU", BigDecimal.ZERO, 0, BigDecimal.ZERO, 0,
        java.util.List.of());
  }
}
