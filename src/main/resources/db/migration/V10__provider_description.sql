-- Perfil editable del proveedor (self-service, Ola 2): descripción corta
-- que hoy solo ops podía cargar/corregir. Default null — proveedores
-- existentes (ej. Melissa, provider #10 en prod) quedan sin descripción
-- hasta que la carguen desde su panel, sin forzar texto vacío.
--
-- Igual que V4/V6/V8/V9: válida para PostgreSQL (prod, flyway ON); en
-- dev/test flyway está OFF y ddl-auto=update cubre la entidad JPA — esta
-- migración no corre ahí pero se mantiene sincronizada.

ALTER TABLE providers ADD COLUMN description varchar(500);
