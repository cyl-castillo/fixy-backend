-- Fase 5 (roadmap "Fixy referencia de ofertas" §5): panel self-service del
-- comercio por link con token, SIN password.
--
-- businesses.panel_token: token URL-safe (SecureRandom, ver
-- BusinessService.ensurePanelLink) del link que el dueño usa para entrar a
-- su panel — LAZY: se genera recién la primera vez que ops pide el link
-- (POST /api/businesses/{id}/panel-link), no en el alta del comercio. Único
-- cuando no es null — mismo criterio que uk_providers_google_sub (V11): en
-- H2 y en Postgres, NULL no colisiona consigo mismo en un índice único, así
-- que los comercios sin panel todavía conviven sin problema.
--
-- push_subscriptions.business_id: liga una suscripción push al comercio
-- dueño — la setea POST /api/public/push-subscriptions cuando el body trae
-- un merchantToken que resuelve a un business (ver
-- PushNotificationService.upsertPublicSubscription). Campo independiente de
-- lead_id/provider_id (V8/V9): nunca los pisa, una fila puede en teoría
-- tener más de uno seteado si el mismo dispositivo pasó por más de un flujo.
--
-- push_subscriptions.last_merchant_reminder_at: throttle propio (máx 1 por
-- día por suscripción) del aviso "tu oferta vence en 2 días"
-- (MerchantOfferExpiryScheduler) — mismo patrón que last_saved_reminder_at
-- (V22) y last_offers_digest_at (V16), cada aviso con su propia marca de
-- tiempo en vez de compartir una sola.
--
-- Igual que V8/V9/V16/V22: válida para PostgreSQL (prod, flyway ON); en
-- dev/test flyway está OFF y ddl-auto=update cubre la entidad JPA — esta
-- migración no corre ahí pero se mantiene sincronizada.

ALTER TABLE businesses ADD COLUMN panel_token varchar(64);
CREATE UNIQUE INDEX IF NOT EXISTS uk_businesses_panel_token ON businesses(panel_token);

ALTER TABLE push_subscriptions ADD COLUMN business_id bigint;
ALTER TABLE push_subscriptions ADD COLUMN last_merchant_reminder_at timestamp(6) with time zone;
