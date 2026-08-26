package com.fixy.backend.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/public/businesses/{slug}} (Fase 3 de la mutación hacia
 * ficha, gap analysis 2026-08-25 §3): la página pública del comercio. Solo
 * responde para comercios {@code ACTIVE} con slug asignado — 404 opaco en
 * cualquier otro caso (ver {@code PublicBusinessService}).
 *
 * <p>Deliberadamente NO incluye {@code whatsappNumber} ni {@code
 * panelToken}: el canal de contacto es Fixy (mismo criterio de privacidad
 * que {@code OfferPublicResponse}, que tampoco expone el WhatsApp del
 * comercio).
 *
 * <p>{@code catalog} trae TODOS los niveles de {@code confidence}, incluidos
 * ítems con {@code available=false} — el front decide cómo mostrarlos (ej.
 * tachado "ya no disponible"), el backend no filtra por confianza acá (a
 * diferencia del motor de respuesta de {@code CatalogAnswerService}, que sí
 * exige cierta confianza para contestar "sí" con seguridad).
 *
 * <p>{@code viewCount} aplica el MISMO umbral de social proof que {@code
 * OfferPublicResponse} ({@code fixy.offers.social-proof-min-views}): por
 * debajo del umbral viaja {@code null}.
 */
public record BusinessPublicResponse(
    Long id,
    String slug,
    String name,
    String category,
    String categories,
    String primaryZone,
    String address,
    Double latitude,
    Double longitude,
    String description,
    List<Hour> hours,
    List<CatalogItem> catalog,
    List<OfferSummary> offers,
    Long viewCount
) {
  public record Hour(int dayOfWeek, String opensAt, String closesAt, String note) {
  }

  /** {@code id} se agregó de forma aditiva (contrato con el frontend, Fase
   * 3): el front keyea la lista por id de fila en vez de label+index, que
   * no es estable si dos ítems comparten label. */
  public record CatalogItem(
      Long id, String label, String kind, Integer priceFrom, String confidence,
      OffsetDateTime verifiedAt, boolean available
  ) {
  }

  public record OfferSummary(Long id, String title, String discountText, OffsetDateTime validUntil, String photoUrl) {
  }
}
