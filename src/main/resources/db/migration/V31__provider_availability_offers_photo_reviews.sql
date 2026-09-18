-- Tier 2 (contrato TIER2_CONTRATO.md, 2026-09-18): ventanas de disponibilidad
-- + rotación por respuesta relativa (§A), asignación con nombre/foto/franja
-- + hora límite de búsqueda (§B), reseña verificada a las 24h + detractores a
-- un humano + respuesta pública (§C). Mismo criterio que V28/V29/V30: ALTER
-- ... ADD COLUMN IF NOT EXISTS sobre tablas existentes (prod es Flyway con
-- ddl-auto=validate); en dev/test flyway está OFF y ddl-auto=update cubre
-- las entidades JPA — esta migración no corre ahí pero se mantiene
-- sincronizada con las entidades. Sin JSONB: H2 en dev/test.

-- §A.1: ventanas de disponibilidad del proveedor (string plano, formato
-- "lun=08-20;mar=08-20;..."). Null/vacío = siempre disponible (compat con
-- los proveedores existentes).
ALTER TABLE providers ADD COLUMN IF NOT EXISTS availability_windows varchar(200);

-- §B.1: foto del proveedor, misma convención que las fotos de lead
-- (fixy.uploads.url-prefix + path relativo, URL absoluta).
ALTER TABLE providers ADD COLUMN IF NOT EXISTS photo_url varchar(500);

-- §A.2: registro de ofertas uno-a-uno (lead, provider, cuándo, si estaba en
-- ventana, cuándo/cómo respondió). Reemplaza la reconstrucción de latencia
-- desde el timeline — fuente única para el score de respuesta del §A.3.
CREATE TABLE IF NOT EXISTS provider_offers (
  id bigserial PRIMARY KEY,
  lead_id bigint NOT NULL,
  provider_id bigint NOT NULL,
  context varchar(24) NOT NULL,
  offered_at timestamptz NOT NULL,
  in_window boolean NOT NULL DEFAULT true,
  responded_at timestamptz,
  response varchar(16)
);
CREATE INDEX IF NOT EXISTS ix_provider_offers_provider_offered
  ON provider_offers (provider_id, offered_at DESC);
CREATE INDEX IF NOT EXISTS ix_provider_offers_lead
  ON provider_offers (lead_id);

-- §B.2/§B.3: franja del técnico (texto corto) y hora límite de búsqueda del
-- pedido (para el mensaje honesto "sigo buscando hasta las HH:mm").
ALTER TABLE leads ADD COLUMN IF NOT EXISTS arrival_window varchar(120);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS search_deadline_at timestamptz;

-- §C.3: respuesta pública del proveedor a la reseña del cliente.
ALTER TABLE lead_ratings ADD COLUMN IF NOT EXISTS provider_reply varchar(500);
ALTER TABLE lead_ratings ADD COLUMN IF NOT EXISTS provider_reply_at timestamptz;
