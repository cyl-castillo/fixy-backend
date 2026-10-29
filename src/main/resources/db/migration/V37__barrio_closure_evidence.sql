-- Avisá, cierre verificable C1 (AVISA_CIERRE_C1_CONTRATO.md, 2026-10-29): pruebas independientes por
-- cierre (ubicación, fecha y huella de la foto, comparación antes/después de la IA) y su veredicto
-- determinista FUERTE / DEBIL, en modo sombra junto a la decisión humana. Nada de esto es público.
-- Mismo criterio que V28–V36: IF NOT EXISTS (idempotente), sin FK (rollback-safe).
-- En dev/test flyway está OFF y ddl-auto cubre la entidad BarrioClosureClaim.

ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS lat double precision NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS lng double precision NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS accuracy_m double precision NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS distance_m double precision NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS photo_taken_at timestamp(6) with time zone NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS photo_dhash bigint NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS dhash_min_distance integer NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS ai_verdict varchar(12) NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS ai_same_place boolean NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS ai_issue_gone boolean NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS ai_reused boolean NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS ai_confidence double precision NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS ai_summary text NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS ai_reasons text NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS ai_reviewed_at timestamp(6) with time zone NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS ai_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS evidence_verdict varchar(12) NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS evidence_reasons text NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS human_decision varchar(12) NULL;
ALTER TABLE barrio_closure_claims ADD COLUMN IF NOT EXISTS human_decided_at timestamp(6) with time zone NULL;

-- agent_turns.kind era varchar(16); BARRIO_CLOSURE_REVIEW tiene 21 caracteres. Ensanchar es idempotente y sin pérdida.
ALTER TABLE agent_turns ALTER COLUMN kind TYPE varchar(32);
