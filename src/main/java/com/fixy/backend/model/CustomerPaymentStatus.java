package com.fixy.backend.model;

/**
 * Estado de un {@link CustomerPayment} (cargo al vecino, no al técnico).
 *
 * PENDING: creado, esperando pago (o el link, si MP estaba apagado al
 * crearlo — nace igual en PENDING sin link).
 * PAID: confirmado por webhook de Mercado Pago o marca manual de ops.
 * WAIVED: Fixy decide no cobrarlo (cortesía, error, ajuste manual).
 */
public enum CustomerPaymentStatus {
  PENDING,
  PAID,
  WAIVED
}
