package com.fixy.backend.model;

/**
 * Estado de la comisión de Fixy sobre un {@link LeadPayment}.
 *
 * PENDING: comisión calculada, esperando pago del proveedor.
 * PAID: confirmado por webhook de Mercado Pago (o marcado manual en reconciliación).
 * WAIVED: Fixy decide no cobrarla (cortesía, error, ajuste manual).
 * OVERDUE: PENDING que superó el plazo esperado de pago (hoy no se calcula
 * automáticamente — reservado para H1.4, recordatorio 48h, que queda como
 * follow-up de esta épica).
 */
public enum CommissionStatus {
  PENDING,
  PAID,
  WAIVED,
  OVERDUE
}
