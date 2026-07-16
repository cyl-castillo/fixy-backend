-- Audiencia por mensaje: separa lo que ve el cliente de lo que ve el
-- proveedor en el chat del lead. Default 'all' preserva el comportamiento
-- histórico (todo lo posteado hasta hoy sigue visible para ambos).
--
-- Igual que V4/V8: válida para PostgreSQL (prod, flyway ON); en dev/test
-- flyway está OFF y ddl-auto=update cubre la entidad JPA — esta migración
-- no corre ahí pero se mantiene sincronizada.

ALTER TABLE lead_messages ADD COLUMN audience varchar(16) NOT NULL DEFAULT 'all';
