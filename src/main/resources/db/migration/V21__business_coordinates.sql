-- Coordenadas del comercio (alta pública de ofertas, fase 2 del roadmap
-- "ofertas protagonistas"): el pin en el mapa llega en fase 3, esto solo
-- reserva el dato cuando el navegador del comerciante lo puede dar gratis
-- (geolocalización del form). Nullable a propósito, mismo criterio que
-- V17__business_address: no forzamos backfill ni valor default, la mayoría
-- de los comercios existentes (altas por ops, ingesta scrapeada) no lo tiene.
--
-- Igual que V17/V20: válida para PostgreSQL (prod, flyway ON); en dev/test
-- flyway está OFF y ddl-auto=update cubre la entidad JPA — esta migración
-- no corre ahí pero se mantiene sincronizada.

ALTER TABLE businesses ADD COLUMN latitude double precision;
ALTER TABLE businesses ADD COLUMN longitude double precision;
