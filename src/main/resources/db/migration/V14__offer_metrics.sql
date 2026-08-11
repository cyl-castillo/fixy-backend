-- Superficie de cliente de Ofertas, Fase 2 (roadmap Historia 3.3, sin tab):
-- contadores simples de vista/click por oferta, expuestos SOLO en el DTO
-- admin. Sin idempotencia (es un contador, no un evento auditado) y sin
-- rate-limit (volumen bajo hoy) — ver POST /api/public/offers/{id}/view y
-- /click.

ALTER TABLE offers ADD COLUMN view_count integer NOT NULL DEFAULT 0;
ALTER TABLE offers ADD COLUMN click_count integer NOT NULL DEFAULT 0;
