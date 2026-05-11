package com.fixy.backend.dto;

import java.util.List;

/**
 * Preview público y agregado de proveedores que matchean (category, zone).
 * Pensado para mostrar confianza pre-submit ("Tenemos N plomeros en Solymar")
 * sin exponer teléfonos ni datos personales del proveedor.
 */
public record ProviderPublicPreview(
    int count,
    List<Item> sample
) {
  public record Item(
      String name,
      String primaryZone,
      String categories,
      Integer completedJobsCount,
      Double ratingAverage
  ) {
  }
}
