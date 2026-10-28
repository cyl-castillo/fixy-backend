-- Avisá IA revisora, Fase 2 (AVISA_IA_F2_CONTRATO.md, 2026-10-28): la IA publica sola los casos
-- claros. barrio_settings guarda el interruptor en tiempo real (sin filas: el default lo da la
-- property fixy.barrio.ai-review.auto-approve). Mismo criterio que V28–V35: IF NOT EXISTS, sin FK.
-- En dev/test flyway está OFF y ddl-auto cubre las entidades.

CREATE TABLE IF NOT EXISTS barrio_settings (
  key varchar(64) PRIMARY KEY,
  value text NOT NULL,
  updated_at timestamp(6) with time zone NULL
);

ALTER TABLE barrio_reports ADD COLUMN IF NOT EXISTS auto_published_at timestamp(6) with time zone NULL;
