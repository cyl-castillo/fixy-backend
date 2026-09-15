package com.fixy.backend.model;

/**
 * Tipo de cobro de Fixy al VECINO (no al técnico — ver {@link
 * com.fixy.backend.model.CommissionStatus} para eso). Refundación de Fixy,
 * fase 2 (contrato REFUNDACION_FASE2_CONTRATO.md §A/§B).
 *
 * SERVICE_FEE: cargo de servicio sobre un trabajo puntual (garantía Fixy 30
 * días + reseña verificada + recibo), ligado a un {@code lead}.
 * PLAN_MONTHLY: cuota mensual del plan Casa a distancia, ligada a un {@code
 * remote_care_plan} (sin lead).
 */
public enum CustomerPaymentKind {
  SERVICE_FEE,
  PLAN_MONTHLY
}
