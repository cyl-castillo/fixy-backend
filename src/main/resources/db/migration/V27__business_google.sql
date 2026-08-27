-- Google Sign-In del DUEÑO del comercio (Fase 1, pedido de Carlos 2026-08-27) --
-- mismo patrón que V11__provider_google.sql para proveedores: sub estable
-- como llave de la cuenta vinculada, email guardado aparte (primera vez que
-- Fixy conoce el email real del dueño). IF NOT EXISTS por el mismo motivo
-- que V11: en prod (ddl-auto=validate) todo cambio de esquema entra por
-- Flyway, nunca a mano.

ALTER TABLE businesses ADD COLUMN IF NOT EXISTS google_sub varchar(255);
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS google_email varchar(255);
CREATE UNIQUE INDEX IF NOT EXISTS uk_businesses_google_sub ON businesses(google_sub);
