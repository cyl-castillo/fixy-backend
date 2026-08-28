package com.fixy.backend.dto;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Métricas de negocio agregadas para el panel de ops, sobre una ventana
 * [from, to).
 *
 * @param from                        inicio de la ventana (inclusive)
 * @param to                          fin de la ventana (exclusive)
 * @param totalLeadsCreated           total de leads creados dentro de la ventana
 * @param fillRatePercentage          % de leads (creados en la ventana) cuyo status ACTUAL está
 *                                    en {ASSIGNED, IN_PROGRESS, COMPLETED}. Ver comentario de
 *                                    simplificación en OpsMetricsService (no hay historial de
 *                                    estados, solo status vigente).
 * @param leadsByStatus               conteo de leads por status, dentro de la ventana
 * @param medianTimeToFirstResponseSeconds mediana (segundos) del tiempo entre el primer
 *                                    PROVIDER_CONTACTED de un lead y la primera respuesta
 *                                    (PROVIDER_ACCEPTED o PROVIDER_REJECTED) posterior a ese
 *                                    primer contacto. null si no hay ningún lead con respuesta
 *                                    registrada en la ventana.
 * @param leadsConsideredForResponseTime cantidad de leads con un tiempo de respuesta válido
 *                                    (usados para calcular la mediana)
 * @param distinctClientsWithCompleted clientes distintos (teléfono normalizado) con al menos un
 *                                    lead COMPLETED en la ventana (denominador del repeat rate)
 * @param repeatClients               clientes distintos con 2+ leads COMPLETED en la ventana
 * @param repeatRateAutodeclaredPercentage % de clientes con lead COMPLETED que repitieron (2+
 *                                    COMPLETED) dentro de la ventana. "Autodeclarado" porque hoy
 *                                    COMPLETED lo marca el proveedor desde su panel sin
 *                                    confirmación del cliente; cuando exista el loop de cierre
 *                                    (P0-2) la fuente pasa a ser el COMPLETED confirmado.
 * @param stalledLeads48h             CONTRATO con el frontend, nombre exacto: leads abiertos (ni
 *                                    COMPLETED ni CANCELLED) sin proveedor que haya aceptado
 *                                    (NEW/IN_REVIEW/PROVIDER_CONTACTED) con más de 48h desde su
 *                                    creación, excluyendo tráfico smoke. Snapshot del momento de
 *                                    la consulta — no está atado a la ventana from/to (es backlog
 *                                    actual, no histórico del rango pedido).
 */
public record OpsDailyMetricsResponse(
    OffsetDateTime from,
    OffsetDateTime to,
    long totalLeadsCreated,
    double fillRatePercentage,
    Map<String, Long> leadsByStatus,
    Long medianTimeToFirstResponseSeconds,
    long leadsConsideredForResponseTime,
    long distinctClientsWithCompleted,
    long repeatClients,
    double repeatRateAutodeclaredPercentage,
    int stalledLeads48h
) {
}
