package com.fixy.backend.dto;

/**
 * Body opcional de PATCH /api/ops/payments/{id}/mark-paid. {@code note}
 * describe el medio de cobro manual (ej. "transferencia Nueva Era") — si
 * viene vacío o el body no se envía, se usa "transferencia" por default.
 */
public record MarkPaymentPaidRequest(String note) {
}
