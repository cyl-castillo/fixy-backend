-- Notas de voz en el chat del cliente: el audio se guarda en uploads y el
-- mensaje lleva su URL relativa; text pasa a ser la transcripción. Nullable:
-- los mensajes de texto de siempre no cambian.
--
-- Igual que V4/V8/V9: válida para PostgreSQL (prod, flyway ON); en dev/test
-- flyway está OFF y ddl-auto=update cubre la entidad JPA — esta migración
-- no corre ahí pero se mantiene sincronizada.

ALTER TABLE lead_messages ADD COLUMN audio_url varchar(300);
