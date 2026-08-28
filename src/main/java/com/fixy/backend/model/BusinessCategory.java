package com.fixy.backend.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Catálogo de RUBROS DE COMERCIO — Fase 1+2 "puerta única de registro"
 * (Carlos 2026-08-27). Distinto de {@link ServiceCategory} (oficio de
 * PROVEEDOR: plomería, electricidad...): acá va lo que vende o hace un
 * comercio de barrio (panadería, kiosco, farmacia...).
 *
 * <p>Antes de este enum, {@link Business#category} era texto libre sin
 * catálogo (ver FIXY_OFERTAS_INGESTA_DESIGN.md §3.1) — prod acumuló valores
 * dispares cargados a mano por Carlos (supermercado, cine, kiosco, estación
 * de servicio, restaurante... ver CURRENT_WORK.md 2026-08-26) que este enum
 * NO reemplaza retroactivamente: las filas existentes quedan como están, la
 * validación contra este catálogo solo corre cuando {@code category} se
 * ESCRIBE de acá en más (alta pública de comercio, alta/edición admin). Los
 * 4 legacy (gastronomia/tienda/servicios/otro) — usados hoy por el wizard
 * público de ofertas ({@code PublishOfferWizard.tsx}) y ya listados en
 * {@code catalog.ts} del frontend — se preservan tal cual para no romper
 * datos ni flujos existentes.
 *
 * <p>{@code categories} (plural, CSV libre) sigue sin catálogo — ver
 * javadoc de {@link Business#categories}, esto no lo toca.
 */
public enum BusinessCategory {
  PANADERIA("panaderia", "panadería"),
  CARNICERIA("carniceria", "carnicería"),
  VERDULERIA("verduleria", "verdulería"),
  ALMACEN("almacen", "almacén"),
  KIOSCO("kiosco", "kiosco"),
  FERRETERIA("ferreteria", "ferretería"),
  FARMACIA("farmacia", "farmacia"),
  // --- legacy: ya en catalog.ts del frontend y en datos de prod, se preservan tal cual ---
  GASTRONOMIA("gastronomia", "gastronomía"),
  TIENDA("tienda", "tienda"),
  SERVICIOS("servicios", "servicios"),
  // --- nuevos, sumados a los legacy ---
  MASCOTAS("mascotas", "mascotas"),
  BELLEZA("belleza", "belleza"),
  OTRO("otro", "otro");

  private final String id;
  private final String label;

  BusinessCategory(String id, String label) {
    this.id = id;
    this.label = label;
  }

  /** Valor persistido en {@code Business.category}. */
  public String id() {
    return id;
  }

  /** Nombre en español para mostrar (con tildes). */
  public String label() {
    return label;
  }

  public static final List<String> ALL_IDS = Arrays.stream(values())
      .map(BusinessCategory::id)
      .toList();

  public static Optional<BusinessCategory> fromId(String rawId) {
    if (rawId == null || rawId.isBlank()) {
      return Optional.empty();
    }
    String normalized = rawId.toLowerCase(Locale.ROOT).trim();
    return Arrays.stream(values()).filter(c -> c.id.equals(normalized)).findFirst();
  }
}
