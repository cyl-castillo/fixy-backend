package com.fixy.backend.config;

import com.fixy.backend.service.PushNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Backfill de {@code push_subscriptions.zone} (Fase Push-1,
 * FIXY_OFERTAS_PUSH_Y_MAPA.md §3): las suscripciones de cliente dadas de
 * alta antes de la migración V16 quedaron con {@code zone IS NULL} — la
 * columna existe recién con este deploy, {@code saveSubscriptionForLead}
 * solo resuelve la zona en altas NUEVAS. Corre en cada boot (mismo patrón
 * {@link CommandLineRunner} que {@link ProviderSeedConfig}): barato con el
 * volumen actual (~30 filas) e idempotente (solo toca {@code zone IS
 * NULL}), así que no hace falta guardarlo como "ya corrió una vez".
 */
@Configuration
public class PushSubscriptionZoneBackfillConfig {

  private static final Logger log = LoggerFactory.getLogger(PushSubscriptionZoneBackfillConfig.class);

  @Bean
  CommandLineRunner backfillPushSubscriptionZones(PushNotificationService pushNotificationService) {
    return args -> {
      int updated = pushNotificationService.backfillMissingZones();
      if (updated > 0) {
        log.info("push_subscriptions: {} suscripción(es) con zona resuelta por backfill", updated);
      }
    };
  }
}
