-- Ingesta automática diaria de ofertas desde fuentes públicas curadas
-- (páginas de beneficios de bancos uruguayos), publicación SIEMPRE mediada
-- por aprobación humana (origin scraped_source nace DRAFT como cualquier
-- otra oferta). Ver maquina/scripts/ofertas-fuentes/ y OfferService.ingest.

ALTER TABLE offers ADD COLUMN source_name varchar(255);
ALTER TABLE offers ADD COLUMN source_url varchar(500);
ALTER TABLE offers ADD COLUMN external_key varchar(255);
ALTER TABLE offers ADD COLUMN all_zones boolean NOT NULL DEFAULT false;

-- Dedup: OfferService.ingest busca por external_key antes de crear. Índice
-- simple (no unique constraint) — el control de unicidad real vive en la
-- lógica de upsert de la app, no en la base (evita que una corrida
-- concurrente del scraper tumbe con una violación de constraint).
CREATE INDEX ix_offers_external_key ON offers (external_key);

-- Limpieza automática de la cola (OfferService.ingest): candidatos son las
-- ofertas scraped de UNA fuente dada que ya no vinieron en la corrida.
CREATE INDEX ix_offers_origin_source_name ON offers (origin, source_name);
