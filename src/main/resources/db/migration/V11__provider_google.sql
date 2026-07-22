-- Google Sign-In del proveedor (login + autoregistro, 2026-07-22).
-- IF NOT EXISTS a propósito: el deploy salió sin esta migración (incidente
-- 2026-07-22, ~20 min de 500 en todo lo que lista proveedores) y las
-- columnas se aplicaron a mano en prod para restaurar el servicio — esta
-- migración las registra en Flyway sin fallar sobre lo ya existente.
-- LECCIÓN: en prod (Postgres + Flyway) el ddl-auto=update es un no-op roto;
-- TODO cambio de esquema entra por migración.

ALTER TABLE providers ADD COLUMN IF NOT EXISTS google_sub varchar(255);
ALTER TABLE providers ADD COLUMN IF NOT EXISTS google_email varchar(255);
CREATE UNIQUE INDEX IF NOT EXISTS uk_providers_google_sub ON providers(google_sub);
