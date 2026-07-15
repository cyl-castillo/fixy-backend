-- Cierre visible de disputas (Ola 2 MVP): la disputa deja de ser un flag
-- ciego. Estos campos guardan CUÁNDO y CON QUÉ NOTA ops la resolvió; el
-- flag `disputed` original no se toca (queda como historia del lead).
--
-- Igual que V4/V6: válida para PostgreSQL (prod, flyway ON); en dev/test
-- flyway está OFF y ddl-auto=update cubre la entidad JPA — esta migración
-- no corre ahí pero se mantiene sincronizada.

ALTER TABLE leads ADD COLUMN dispute_resolved_at timestamp with time zone;
ALTER TABLE leads ADD COLUMN dispute_resolution_note varchar(1000);
