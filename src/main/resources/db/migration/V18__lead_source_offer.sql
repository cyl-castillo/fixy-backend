-- CTA "Pedir por Fixy" (FIXY_OFERTAS_CTA_DESIGN.md §3.2): vínculo
-- oferta→lead para medir conversión. Plain Long, sin FK real ni relación
-- JPA — mismo criterio que Offer.businessId y Lead.assignedProviderId en
-- todo el repo.
--
-- Nullable a propósito: null si el lead no vino de una oferta (la inmensa
-- mayoría de los leads hoy). Sin índice: volumen bajo, no hace falta.

ALTER TABLE leads ADD COLUMN source_offer_id bigint;
