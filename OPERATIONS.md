# Fixy Backend Operations

## Estado operativo actual

- Runtime esperado: Java local
- App bind: `127.0.0.1:8080`
- Health endpoint: `/api/health`
- Log principal: `/var/log/fixy-backend.log`
- Base de datos: este doc describe la instancia LOCAL de `fixy-backend.service`
  (`127.0.0.1:8080` en esta máquina), que usa H2 archivo local en `./data/fixy`
  — solo para dev/verificación. **Prod (AWS Lightsail `fixy-prod`) usa
  Postgres + Flyway** (`SPRING_JPA_HIBERNATE_DDL_AUTO=validate`,
  `SPRING_FLYWAY_ENABLED=true`, ver `deploy/aws/fixy-backend.env.example` y
  `db/migration/V1__baseline.sql` en adelante) — Hibernate NO es dueño del
  esquema de prod desde el incidente 2026-07-22. Ver
  `fixy-docs/infrastructure/aws.md`.
- Servicio activo esperado: `fixy-backend.service` (systemd del sistema)
- Se removió `AUTO_SERVER=TRUE` para evitar exposición TCP extra de H2.

## Variables relevantes

Requeridas para ops:

- `FIXY_OPS_USERNAME`
- `FIXY_OPS_PASSWORD`

Actualmente se cargan desde:

- `/etc/fixy-backend.env`

Opcionales:

- `OPENAI_API_KEY`
- `OPENAI_MODEL`
- `PORT`

## Verificación rápida

```bash
cd /home/father/Documents/workspaces/fixy-backend
bash scripts/status.sh
```

## Healthcheck simple

```bash
cd /home/father/Documents/workspaces/fixy-backend
bash scripts/healthcheck.sh
```

## Qué validar siempre

1. Que el servicio `fixy-backend.service` esté activo.
2. Que escuche en `127.0.0.1:8080`.
3. Que `GET /api/health` responda 200.
4. Que NO exista listener inesperado de H2.
5. Que el log no muestre errores de arranque o placeholders faltantes.

## Riesgos operativos actuales

- H2 (esta instancia local) sirve para dev/test; prod ya migró a Postgres + Flyway (ver arriba).
- El backend ya corre como servicio, pero el acceso público todavía depende de exposición temporal o futura integración formal con Cloudflare Tunnel.
- La UI interna usa auth básica; alcanza para esta etapa, pero más adelante conviene una capa de acceso más seria.

## Política actual de acceso

Público:
- `/`
- `/api/health`
- `/api/intake`
- `/api/public/**`

Protegido con HTTP Basic Auth:
- `/ops.html`
- `/api/leads/**`
- `/api/providers/**`

Credenciales:
- `FIXY_OPS_USERNAME`
- `FIXY_OPS_PASSWORD`

Verificación rápida de protección:

```bash
cd /home/father/Documents/workspaces/fixy-backend
bash scripts/verify_ops.sh
```

## Operación del servicio

Unidad activa:

- `/etc/systemd/system/fixy-backend.service`

Variables sensibles:

- `/etc/fixy-backend.env`

Comandos útiles:

```bash
systemctl status fixy-backend.service
sudo systemctl restart fixy-backend.service
sudo systemctl stop fixy-backend.service
sudo systemctl start fixy-backend.service
journalctl -u fixy-backend.service -n 100 --no-pager
bash scripts/logs.sh
```

## Backup básico

Comando manual:

```bash
cd /home/father/Documents/workspaces/fixy-backend
bash scripts/backup_data.sh
```

Backup + poda:

```bash
cd /home/father/Documents/workspaces/fixy-backend
bash scripts/backup_and_prune.sh
```

Documentación:

- `BACKUPS.md`
- `RECOVERY.md`

## Recomendación siguiente

- Mantener docs alineadas al servicio systemd real del host.
- Más adelante migrar persistencia a una base más apropiada para producción.
- Incorporar backup externo o snapshot remoto cuando suba la criticidad.
