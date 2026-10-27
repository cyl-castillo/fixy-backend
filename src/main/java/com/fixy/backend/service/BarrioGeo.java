package com.fixy.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Geografía de Fixy Barrio (B1): caja de cobertura de Ciudad de la Costa, distancia haversine
 * (dedupe ≤ 40 m) y barrio por centroide más cercano (lista en {@code domain/barrio-zones.yml}).
 * Funciones puras salvo la carga perezosa del YAML.
 */
public final class BarrioGeo {

  private static final Logger log = LoggerFactory.getLogger(BarrioGeo.class);

  public static final double LAT_MIN = -34.90;
  public static final double LAT_MAX = -34.74;
  public static final double LNG_MIN = -56.10;
  public static final double LNG_MAX = -55.80;

  public static final String RESOURCE = "domain/barrio-zones.yml";
  private static final double EARTH_RADIUS_M = 6_371_000.0;

  public record Zone(String name, double lat, double lng) {}

  /** Lista de barrios + regla de corte; inmutable. */
  public record Zones(List<Zone> zones, double maxDistanceKm, String defaultZone) {}

  private static volatile Zones cached;

  private BarrioGeo() {
  }

  public static boolean inCoverage(double lat, double lng) {
    return lat >= LAT_MIN && lat <= LAT_MAX && lng >= LNG_MIN && lng <= LNG_MAX;
  }

  public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
    double dLat = Math.toRadians(lat2 - lat1);
    double dLng = Math.toRadians(lng2 - lng1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
        * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
  }

  /** Barrio del centroide más cercano; si el más cercano está a más de {@code maxDistanceKm},
   * el nombre por defecto ("Ciudad de la Costa"). */
  public static String zoneFor(double lat, double lng, Zones config) {
    Zone best = null;
    double bestMeters = Double.MAX_VALUE;
    for (Zone zone : config.zones()) {
      double meters = haversineMeters(lat, lng, zone.lat(), zone.lng());
      if (meters < bestMeters) {
        bestMeters = meters;
        best = zone;
      }
    }
    if (best == null || bestMeters > config.maxDistanceKm() * 1000.0) {
      return config.defaultZone();
    }
    return best.name();
  }

  public static String zoneFor(double lat, double lng) {
    return zoneFor(lat, lng, zones());
  }

  public static Zones zones() {
    Zones local = cached;
    if (local == null) {
      synchronized (BarrioGeo.class) {
        if (cached == null) {
          cached = load();
        }
        local = cached;
      }
    }
    return local;
  }

  @SuppressWarnings("unchecked")
  static Zones load() {
    try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
      Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      Map<String, Object> map = (Map<String, Object>) root;
      List<Zone> zones = new ArrayList<>();
      for (Object item : (List<Object>) map.get("zones")) {
        Map<String, Object> z = (Map<String, Object>) item;
        zones.add(new Zone(String.valueOf(z.get("name")),
            ((Number) z.get("lat")).doubleValue(), ((Number) z.get("lng")).doubleValue()));
      }
      double maxKm = map.get("max_distance_km") instanceof Number n ? n.doubleValue() : 2.5;
      String def = map.get("default_zone") == null ? "Ciudad de la Costa" : String.valueOf(map.get("default_zone"));
      return new Zones(List.copyOf(zones), maxKm, def);
    } catch (IOException | RuntimeException e) {
      log.warn("no se pudo leer {}: los reclamos quedan en 'Ciudad de la Costa'", RESOURCE, e);
      return new Zones(List.of(), 2.5, "Ciudad de la Costa");
    }
  }
}
