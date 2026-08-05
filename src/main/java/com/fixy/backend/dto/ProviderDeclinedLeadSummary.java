package com.fixy.backend.dto;

import java.time.OffsetDateTime;

/**
 * Entrada del historial "no concretados" del panel del proveedor (feedback
 * real de Guillermo/Carnot vía Carlos 2026-08-05: habló con un cliente, no
 * llegaron a nada, y el caso o le estorbaba en el tablero o desaparecía sin
 * rastro al soltarlo). Datos NO sensibles a propósito: sin teléfono ni chat
 * del cliente — el pedido pudo haber pasado a otro proveedor.
 */
public record ProviderDeclinedLeadSummary(
    Long leadId,
    String category,
    String location,
    /** Resumen corto del problema (sin datos de contacto). */
    String problem,
    OffsetDateTime declinedAt,
    /**
     * Qué pasó después con el pedido: "en_busqueda" (sigue libre),
     * "tomado_por_otro", "completado", "cancelado".
     */
    String outcome
) {
}
