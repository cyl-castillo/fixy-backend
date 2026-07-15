-- Disponibilidad MVP del proveedor (agenda/disponibilidad, base de Ola 2):
-- false = "en pausa", no recibe nuevas oportunidades en su bandeja
-- (ProviderOpportunityService.listFor). Default true para no romper a
-- proveedores existentes (ej. Melissa, provider #10 en prod).
--
-- Igual que V4: válida para PostgreSQL (prod, flyway ON); en dev/test
-- flyway está OFF y ddl-auto=update cubre la entidad JPA — esta migración
-- no corre ahí pero se mantiene sincronizada.

ALTER TABLE providers ADD COLUMN accepting_work boolean NOT NULL DEFAULT true;
