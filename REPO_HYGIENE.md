# Fixy Backend Repo Hygiene

## Objetivo
Separar claramente código fuente, artefactos de build, datos locales y backups operativos.

## Qué debe quedar versionado
- `src/`
- `pom.xml`
- `README.md`
- docs operativas (`OPERATIONS.md`, `DEPLOYMENT.md`, `BACKUPS.md`, `RECOVERY.md`)
- scripts necesarios para operación
- configuraciones plantilla no sensibles

## Qué NO debe versionarse
- `target/`
- `data/`
- `backups/`
- logs
- secrets o env files reales
- outputs de test y build

## Estado actual
- `.gitignore` ya excluye correctamente `target/`, `data/`, `backups/`, logs y archivos de IDE.
- El repositorio todavía arrastra historial/estado previo asociado a `backups/`.
- Hay cambios recientes de estabilización de tests:
  - `pom.xml`
  - `src/test/java/com/fixy/backend/LeadControllerTest.java`
  - `src/test/resources/application.yml`

## Recomendación operativa
1. Confirmar y conservar los cambios de tests.
2. Mantener `data/` y `backups/` solo como runtime local, no como contenido del repo.
3. Si se decide limpiar el índice de git para artefactos históricos, hacerlo en una operación consciente y separada.
4. Evitar commits mezclando cambios de producto con datos locales o artefactos de build.

## Próximo cleanup recomendado
Cuando se haga limpieza git formal:
- revisar qué quedó trackeado históricamente en `backups/`
- normalizar el árbol de trabajo
- hacer un commit específico de hygiene del repo

## Principio rector
El repo debe describir cómo construir y operar Fixy, no contener los datos vivos ni los residuos de ejecución.
