-- Fase Push-1 (FIXY_OFERTAS_PUSH_Y_MAPA.md §3): zona del cliente en la
-- suscripción, para poder armarle el digest semanal "las ofertas de tu
-- zona" sin tener que resolver el join a leads en cada envío. Y el
-- rate-limit del digest (máx 1 cada 7 días por suscripción).
--
-- Ambas columnas nullable a propósito:
-- - zone: solo tiene sentido en suscripciones de cliente (lead_id no nulo)
--   y solo si el lead declaró una zona que Fixy reconoce (CoverageZone);
--   las de proveedor quedan siempre en null.
-- - last_offers_digest_at: null hasta el primer envío del digest.
--
-- El backfill de las suscripciones EXISTENTES (resolver zone desde
-- Lead.location vía CoverageZone.fromLabel, mismos alias/jerarquía que el
-- matching) no va en SQL: la resolución de alias vive en código Java
-- (CoverageZone) y duplicarla acá arriesga desincronizarse, el mismo error
-- que motivó CoverageZone en primer lugar (ver su javadoc). Lo hace
-- PushSubscriptionZoneBackfillConfig (CommandLineRunner) al boot, sobre las
-- filas con zone IS NULL — barato (32 filas hoy) e idempotente.
--
-- Igual que V8/V9: válida para PostgreSQL (prod, flyway ON); en dev/test
-- flyway está OFF y ddl-auto=update cubre la entidad JPA — esta migración
-- no corre ahí pero se mantiene sincronizada.

ALTER TABLE push_subscriptions ADD COLUMN zone varchar(255);
ALTER TABLE push_subscriptions ADD COLUMN last_offers_digest_at timestamp(6) with time zone;
