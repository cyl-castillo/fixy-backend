# Fixy Backend Backups

## Objetivo

Dejar un respaldo simple, local y rápido de la base H2 actual mientras `fixy-backend` sigue en etapa temprana.

## Ubicaciones

- Datos activos: `data/`
- Backups locales: `backups/`
- Último backup: `backups/latest`

## Crear backup manual

```bash
cd /home/father/Documents/workspaces/fixy-backend
bash scripts/backup_data.sh
```

## Qué respalda hoy

- `fixy.mv.db`
- `fixy.trace.db`
- cualquier otro archivo dentro de `data/`

## Limitaciones

- Es un backup local, no externo.
- No reemplaza una estrategia seria de snapshots/remoto.
- H2 es aceptable para esta etapa, pero no es la persistencia final deseable.

## Rotación simple

Podar backups antiguos conservando los últimos 7:

```bash
cd /home/father/Documents/workspaces/fixy-backend
bash scripts/prune_backups.sh
```

Crear backup y podar en una sola pasada:

```bash
cd /home/father/Documents/workspaces/fixy-backend
bash scripts/backup_and_prune.sh
```

Puedes cambiar retención con:

```bash
FIXY_BACKUP_KEEP=14 bash scripts/prune_backups.sh
```

## Restauración

Ver `RECOVERY.md`.

## Recomendación siguiente

- Mantener al menos un backup reciente antes de cambios grandes.
- Más adelante: backup externo o snapshot remoto.
