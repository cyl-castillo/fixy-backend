-- Avisá IA revisora, Fase 1 / modo sombra (AVISA_IA_F1_CONTRATO.md, 2026-10-28): la IA opina sobre
-- la foto de cada reclamo y queda guardado junto a la decisión humana para medir coincidencia.
-- Mismo criterio que V28–V34: IF NOT EXISTS (idempotente), defaults, sin FK (rollback-safe).
-- En dev/test flyway está OFF y ddl-auto cubre la entidad BarrioReport.

ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS ai_verdict varchar(12) NULL;
ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS ai_confidence double precision NULL;
ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS ai_reasons text NULL;
ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS ai_suggested_category varchar(16) NULL;
ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS ai_summary text NULL;
ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS ai_reviewed_at timestamp(6) with time zone NULL;
ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS ai_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS human_decision varchar(12) NULL;
ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS human_decided_at timestamp(6) with time zone NULL;
