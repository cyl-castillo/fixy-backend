package com.fixy.backend.dto;

import java.time.OffsetDateTime;
import java.util.List;
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
 *                                    posterior a ese primer contacto. Tier 3 (contrato §A.3):
 *                                    "respuesta" ahora es PROVIDER_ACCEPTED, PROVIDER_DECLINED, o
 *                                    PROVIDER_STATUS_CHANGE (actor provider) con mensaje que
 *                                    termina en "→ ASSIGNED" — antes usaba PROVIDER_ACCEPTED/
 *                                    PROVIDER_REJECTED, que ningún camino actual emite, dejando
 *                                    esta métrica siempre vacía. null si no hay ningún lead con
 *                                    respuesta registrada en la ventana.
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
 * @param realRequests                Refundación fase 1 (contrato §5): leads del rango con
 *                                    categoría detectada Y (al menos un mensaje del cliente O
 *                                    channel = web-order), excluyendo smoke — "pedido real", no
 *                                    un chat que nunca arrancó.
 * @param structuredOrders            leads del rango con {@code serviceCode} no nulo (vinieron
 *                                    del pedido estructurado, no del chat conversacional).
 * @param completedJobs               leads del rango con status COMPLETED.
 * @param emptyChats                  leads del rango sin ningún mensaje del cliente — cuánto
 *                                    ruido elimina el pedido estructurado frente al chat libre.
 * @param serviceFeesCreated          Refundación fase 2 (contrato §A.4.6): cantidad de cargos
 *                                    de servicio (kind SERVICE_FEE) creados dentro de la ventana.
 * @param serviceFeesCollected        suma de {@code amount} de los cargos SERVICE_FEE que se
 *                                    pagaron (paidAt) dentro de la ventana.
 * @param funnel                      Tier 3 (contrato §A.1): embudo "del chat al cobro" —
 *                                    chats → reales → contactados → asignados → terminados →
 *                                    cobrados → reseñados (y de esos, verificados).
 * @param fillRate2hPercentage        Tier 3 (contrato §A.2): % de pedidos reales del rango que
 *                                    llegaron a "asignado" dentro de las 2h desde su creación.
 *                                    Compuerta del día 30. null si no hubo pedidos reales.
 * @param medianOfferResponseMinutes  Tier 3 (contrato §A.3): mediana de minutos de respuesta
 *                                    sobre {@code provider_offers} EN VENTANA respondidas
 *                                    (ACCEPTED/DECLINED) con {@code offeredAt} en el rango. null
 *                                    sin datos.
 * @param offerResponses              desglose de las ofertas del rango (por {@code offeredAt}):
 *                                    total, aceptadas, rechazadas, vencidas (TIMEOUT), pendientes
 *                                    y cuántas fueron en ventana.
 * @param providers                   Tier 3 (contrato §A.4): estadísticas de respuesta por
 *                                    proveedor activo (o con ofertas en el rango), ordenadas por
 *                                    mediana de respuesta ascendente (null al final) — es un
 *                                    ranking de premio, nunca de castigo.
 * @param categories                  Tier 3 (contrato §A.5): salud por categoría activa
 *                                    ({@code fixy.orders.active-categories}).
 * @param alerts                      Tier 3 (contrato §A.6): conteos de eventos que requieren
 *                                    acción de ops dentro de la ventana.
 * @param gates                       Tier 3 (contrato §A.7): compuertas del plan de 90 días
 *                                    (día 30/60/90) evaluadas sobre la ventana pedida, con los
 *                                    números crudos usados para que el frontend no recalcule.
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
    int stalledLeads48h,
    long realRequests,
    long structuredOrders,
    long completedJobs,
    long emptyChats,
    long serviceFeesCreated,
    java.math.BigDecimal serviceFeesCollected,
    Funnel funnel,
    Double fillRate2hPercentage,
    Integer medianOfferResponseMinutes,
    OfferResponses offerResponses,
    List<ProviderResponseStats> providers,
    List<CategoryHealth> categories,
    Alerts alerts,
    Gates gates
) {

  /** Embudo "del chat al cobro" (contrato §A.1). Cada etapa es un conteo de
   * leads del rango pedido (no acumulativo entre ventanas). */
  public record Funnel(
      long chats,
      long real,
      long contacted,
      long assigned,
      long completed,
      long paid,
      long reviewed,
      long verifiedReviews
  ) {
  }

  /** Desglose de {@code provider_offers} con {@code offeredAt} en el rango. */
  public record OfferResponses(
      long total,
      long accepted,
      long declined,
      long timeout,
      long pending,
      long inWindow
  ) {
  }

  /** "Mis números" a nivel ops, por proveedor (contrato §A.4). */
  public record ProviderResponseStats(
      Long id,
      String name,
      List<String> categories,
      boolean openNow,
      String availabilityWindows,
      long offers,
      long accepted,
      long declined,
      long timeout,
      Double acceptanceRatePercentage,
      Integer medianResponseMinutes,
      long completedInRange,
      Double ratingAverage,
      Integer ratingCount
  ) {
  }

  /** Salud de una categoría activa dentro de la ventana (contrato §A.5). */
  public record CategoryHealth(
      String category,
      long activeProviders,
      long openNowProviders,
      long realRequests,
      long assigned,
      Double fillRate2hPercentage,
      Double acceptanceRatePercentage,
      Integer medianResponseMinutes
  ) {
  }

  /** Conteos de eventos que requieren acción de ops (contrato §A.6). */
  public record Alerts(
      long searchDeadlineMissed,
      long muteLeads,
      long lowRatings,
      long reviewRequests,
      long priceChangesProposed,
      long priceChangesRejected
  ) {
  }

  /** Compuertas del plan de 90 días (contrato §A.7), evaluadas sobre la
   * ventana pedida. Los booleanos son null cuando no hay datos suficientes
   * para evaluar la condición (nunca un false engañoso). */
  public record Gates(
      Day30Gate day30,
      Day60Gate day60,
      Day90Gate day90,
      Double fillRate2h,
      Integer medianResponse,
      long completed,
      long paid,
      Double collectionPercentage,
      long repeatClients,
      Double repeatRatePercentage,
      Double ratingAverage
  ) {
  }

  public record Day30Gate(Boolean fillRate2hOk, Boolean responseOk, Boolean supplyOk, Boolean volumeOk) {
  }

  /** {@code collectionKill}: true cuando el cobro cae por debajo del 15% —
   * señal de kill, no solo "no cumple la compuerta". */
  public record Day60Gate(Boolean completedOk, Boolean collectionOk, Boolean collectionKill, Boolean repeatOk) {
  }

  public record Day90Gate(Boolean scaleOk, Boolean repeatRateOk, Boolean ratingOk) {
  }
}
