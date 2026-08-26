-- Fase 3 de la mutación hacia ficha estructurada (gap analysis 2026-08-25):
-- página pública del comercio /comercio/{slug} con JSON-LD LocalBusiness.
-- Igual que V19-V25: válida para PostgreSQL (prod, flyway ON); en dev/test
-- flyway está OFF y ddl-auto=update cubre las entidades JPA — esta
-- migración no corre ahí pero se mantiene sincronizada.

-- slug: identificador URL-safe del comercio, lazy e idempotente (mismo
-- criterio que panel_token, V23): se genera recién cuando hace falta (alta
-- de un Business nuevo, o POST /api/businesses/{id}/public-link — ver
-- BusinessSlugService.ensureSlug), y NUNCA se regenera una vez asignado —
-- las URLs públicas ya compartidas no pueden cambiar. Único cuando no es
-- null: en Postgres NULL no colisiona consigo mismo en un índice único
-- (mismo criterio que uk_businesses_panel_token), así que los comercios sin
-- slug todavía conviven sin problema.
ALTER TABLE businesses ADD COLUMN slug varchar(80);
CREATE UNIQUE INDEX IF NOT EXISTS uk_businesses_slug ON businesses(slug);

-- view_count: contador simple de vistas de la ficha pública. A diferencia de
-- Offer.viewCount (que se suma vía un POST /view separado desde el
-- cliente), acá el propio GET /api/public/businesses/{slug} lo incrementa
-- fire-and-forget (ver PublicBusinessService) — no hay endpoint aparte.
-- Lección conocida del repo (ver BusinessCatalogItem.available, V25):
-- columnDefinition con default TAMBIÉN en la entidad JPA — sin eso, sobre
-- una H2 dev persistente con filas previas el ALTER ADD COLUMN NOT NULL
-- falla en silencio y el arranque queda con la columna faltante.
ALTER TABLE businesses ADD COLUMN view_count bigint NOT NULL DEFAULT 0;
