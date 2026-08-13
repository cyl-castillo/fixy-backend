-- Dirección del comercio: dato barato hoy, mapa mañana. Sin validación de
-- formato (texto libre, ops la carga tal cual la ve en el mostrador).
--
-- Nullable a propósito: los comercios existentes (altas manuales e
-- ingestados por scraping, ver V15) no la tienen cargada — no forzamos un
-- backfill ni un valor default.
--
-- Igual que V10/V16: válida para PostgreSQL (prod, flyway ON); en dev/test
-- flyway está OFF y ddl-auto=update cubre la entidad JPA — esta migración
-- no corre ahí pero se mantiene sincronizada.

ALTER TABLE businesses ADD COLUMN address varchar(255);
