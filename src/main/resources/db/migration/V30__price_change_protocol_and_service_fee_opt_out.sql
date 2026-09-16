-- Tier 1 (contrato TIER1_CONTRATO.md, 2026-09-16): protocolo "al llegar"
-- (§B, Decreto 244/000 art. 9 — todo adicional con aprobación previa) +
-- cargo de servicio decidido al reservar (§C). Mismo criterio que V28/V29:
-- ALTER ... ADD COLUMN IF NOT EXISTS sobre tablas existentes (prod es
-- Flyway con ddl-auto=validate, todo cambio de esquema entra por acá). En
-- dev/test flyway está OFF y ddl-auto=update cubre las entidades JPA — esta
-- migración no corre ahí pero se mantiene sincronizada con Lead.

-- §B.1: propuesta de precio nuevo del proveedor + aceptación/rechazo del
-- cliente. proposed_* se pisa en cada propuesta nueva (nunca acumula
-- historial — el timeline de leads ya registra cada propuesta como evento
-- PRICE_CHANGE_PROPOSED); agreed_* es el registro legal de lo aceptado
-- (no se borra nunca, contrato §B.1).
ALTER TABLE leads ADD COLUMN IF NOT EXISTS proposed_amount numeric(12,2);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS proposed_reason varchar(300);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS proposed_at timestamptz;
ALTER TABLE leads ADD COLUMN IF NOT EXISTS agreed_amount numeric(12,2);
ALTER TABLE leads ADD COLUMN IF NOT EXISTS agreed_at timestamptz;

-- Desvío del contrato (ver TIER1_CONTRATO.md, "Cambios durante
-- implementación (backend)"): el contrato no lista esta columna, pero
-- LeadResponse.priceChange.status necesita distinguir REJECTED de PENDING
-- y de null sin una columna propia (proposed_at/agreed_at solos no
-- alcanzan: un rechazo no borra la propuesta, solo la resuelve). Se pisa a
-- null en cada propuesta nueva, igual que proposed_*.
ALTER TABLE leads ADD COLUMN IF NOT EXISTS price_change_rejected_at timestamptz;

-- §C.2: el cliente eligió "solo el técnico, sin garantía" al reservar.
ALTER TABLE leads ADD COLUMN IF NOT EXISTS service_fee_opt_out boolean NOT NULL DEFAULT false;
