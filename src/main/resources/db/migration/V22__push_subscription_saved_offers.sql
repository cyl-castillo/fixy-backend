-- Fase Push-2 (roadmap "Fixy referencia de ofertas" §4, enganche): guardado
-- de ofertas de un suscriptor (cliente O visitante, nunca proveedor) para
-- poder avisarle cuando la que guardó está por vencer.
--
-- saved_offer_ids: CSV de ids de Offer, null si no guardó ninguna — mismo
-- patrón liviano que Provider.categories (CSV de texto, no tabla aparte: el
-- volumen no lo justifica y evita un JOIN extra en el scheduler horario).
-- Lo escribe POST /api/public/push-subscriptions (upsert por endpoint) y lo
-- lee/limpia SavedOfferReminderScheduler.
--
-- last_saved_reminder_at: rate-limit del recordatorio (máx 1 por día por
-- suscripción), null hasta el primer envío — mismo patrón que
-- last_offers_digest_at (V16).
--
-- Igual que V8/V9/V16: válida para PostgreSQL (prod, flyway ON); en dev/test
-- flyway está OFF y ddl-auto=update cubre la entidad JPA — esta migración no
-- corre ahí pero se mantiene sincronizada.

ALTER TABLE push_subscriptions ADD COLUMN saved_offer_ids varchar(2000);
ALTER TABLE push_subscriptions ADD COLUMN last_saved_reminder_at timestamp(6) with time zone;
