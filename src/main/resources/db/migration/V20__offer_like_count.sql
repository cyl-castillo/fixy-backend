-- Superficie de cliente de Ofertas, Fase 3 (motor de ranking — interacción
-- real del barrio): contador de "Me sirve" del frontend, mismo criterio que
-- view_count/click_count (V14) — sin idempotencia ni rate-limit, expuesto en
-- el DTO admin y (a diferencia de view_count/click_count) también en el
-- público, ver POST /api/public/offers/{id}/like.
--
-- Igual que V10/V12/V14: válida para PostgreSQL (prod, flyway ON); en
-- dev/test flyway está OFF y ddl-auto=update cubre la entidad JPA — esta
-- migración no corre ahí pero se mantiene sincronizada.

ALTER TABLE offers ADD COLUMN like_count integer NOT NULL DEFAULT 0;
