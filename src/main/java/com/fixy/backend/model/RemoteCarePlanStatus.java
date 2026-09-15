package com.fixy.backend.model;

/**
 * Estado de un {@link RemoteCarePlan} (Refundación de Fixy, fase 2,
 * contrato §B.2).
 *
 * REQUESTED: el dueño pidió el plan, ops todavía no lo activó.
 * ACTIVE: ops lo activó — el dueño puede pedir servicios remotos y se
 * factura cada 30 días.
 * PAUSED: ops lo pausó (no factura, no acepta pedidos remotos nuevos).
 * CANCELLED: dado de baja.
 */
public enum RemoteCarePlanStatus {
  REQUESTED,
  ACTIVE,
  PAUSED,
  CANCELLED
}
