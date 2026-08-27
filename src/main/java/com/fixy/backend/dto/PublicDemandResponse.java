package com.fixy.backend.dto;

import java.util.List;

/**
 * {@code GET /api/public/demand} — la demanda REAL que hoy está esperando
 * proveedor, agregada por oficio. Solo números: ni un dato del vecino que
 * pidió (ni zona, ni texto, ni fecha), porque el endpoint es público.
 *
 * <p>Lo consume el hero de {@code /sumate} (puerta única de registro) para
 * reemplazar la promesa genérica "que te encuentren en tu barrio" por el
 * hecho concreto: cuántos pedidos hay sin cubrir en cada rubro ahora mismo.
 *
 * @param totalOpen suma de {@code openCount} de las categorías devueltas —
 *                  NO el total de pedidos abiertos del sistema (los de
 *                  oficio desconocido u "otro" no se cuentan; ver
 *                  {@code PublicDemandService}).
 * @param categories oficios con al menos un pedido esperando, de mayor a
 *                  menor. Lista vacía si no hay demanda viva.
 */
public record PublicDemandResponse(
    int totalOpen,
    List<Item> categories
) {

  /**
   * @param category id del oficio, el mismo que usa el alta de proveedor
   *                 ({@code ServiceCategory.id()}) — así el que se suma
   *                 elige exactamente el rubro que vio con demanda.
   * @param label etiqueta humana ya lista para mostrar ("mandados y trámites").
   * @param openCount pedidos de ese oficio esperando proveedor.
   */
  public record Item(String category, String label, int openCount) {
  }
}
