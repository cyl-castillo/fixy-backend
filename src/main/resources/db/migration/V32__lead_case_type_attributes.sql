-- Core Fase 3, pieza 2 (contrato CORE_FASE3_CONTRATO.md, 2026-10-06): el Lead pasa a ser un
-- "Case" con tipo de caso y atributos libres. Mismo criterio que V28: ALTER ... ADD COLUMN IF NOT
-- EXISTS sobre una tabla existente (prod es Flyway con ddl-auto=validate, todo cambio de esquema
-- entra por acá, e idempotente por el incidente de V11). En dev/test flyway está OFF y
-- ddl-auto=update/create-drop cubre la entidad Lead — esta migración no corre ahí pero se
-- mantiene sincronizada con Lead.caseType / Lead.attributes.
--
-- attributes es TEXT con un AttributeConverter (Map<String,Object> <-> JSON), no jsonb: un
-- binding JSON de Hibernate contra Postgres no se prueba en la suite (H2) y, si falla, rompe el
-- INSERT de todos los leads. Texto se bindea igual en H2 y en Postgres. Cuando una consulta lo
-- necesite: ALTER COLUMN attributes TYPE jsonb USING attributes::jsonb.
--
-- Rollback-safe: ambas columnas son NOT NULL con DEFAULT, así el jar anterior (que arma el INSERT
-- sin estas columnas) sigue funcionando con la migración aplicada.

ALTER TABLE leads ADD COLUMN IF NOT EXISTS case_type varchar(40) NOT NULL DEFAULT 'home-services';
ALTER TABLE leads ADD COLUMN IF NOT EXISTS attributes text NOT NULL DEFAULT '{}';
