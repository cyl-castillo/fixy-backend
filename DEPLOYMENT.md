# Fixy Backend Deployment Notes

## Estado actual

- Backend local corriendo en `127.0.0.1:8080`
- UI interna mínima en `/ops.html`
- Protección básica con HTTP Basic Auth sobre `/ops.html`, `/api/leads/**` y `/api/providers/**`
- Servicio systemd activo: `fixy-backend.service`
- Exposición temporal por Cloudflare quick tunnel / futura migración a túnel formal

## Credenciales actuales de ops

Se recomienda NO documentar credenciales activas en texto plano dentro del repo.

- Usuario: definido por variable de entorno `FIXY_OPS_USERNAME`
- Password: definido por variable de entorno `FIXY_OPS_PASSWORD`
- En esta máquina hoy se cargan desde `/etc/fixy-backend.env`

> Verificar valores actuales en el entorno de ejecución y rotarlos antes de un despliegue más serio. No documentar el valor del secreto en el repo.

## URLs actuales

### Local
- `http://127.0.0.1:8080/api/health`
- `http://127.0.0.1:8080/ops.html`

### Pública temporal
- Quick tunnel de Cloudflare (puede cambiar o caer)

## Servicio local

Unidad:

- `/etc/systemd/system/fixy-backend.service`

Variables:

- `/etc/fixy-backend.env`

Comandos:

```bash
systemctl status fixy-backend.service
sudo systemctl restart fixy-backend.service
journalctl -u fixy-backend.service -n 100 --no-pager
```

## Cuando haya dominio

### 1. Login en Cloudflare
```bash
cloudflared tunnel login
```

### 2. Crear túnel nombrado
```bash
cloudflared tunnel create fixy-backend
```

### 3. Crear config real desde plantilla
Copiar `cloudflared/config.template.yml` a una ubicación real, por ejemplo:
```bash
mkdir -p ~/.cloudflared
cp cloudflared/config.template.yml ~/.cloudflared/config.yml
```

Editar:
- `hostname: api.TU_DOMINIO`
- `credentials-file`
- nombre de túnel si cambia

### 4. Crear DNS en Cloudflare
```bash
cloudflared tunnel route dns fixy-backend api.TU_DOMINIO
```

### 5. Correr túnel formal
```bash
cloudflared tunnel run fixy-backend
```

### 6. Instalar como servicio (si se decide)
```bash
sudo cloudflared service install
```

## Seguridad recomendada siguiente

1. Mantener secretos fuera del repo
2. Separar `ops` y `api` si hace falta
3. Reemplazar quick tunnel por named tunnel
4. Agregar una UI de login más seria en el futuro

## Operación actual

### Crear lead
`POST /api/leads`

### Listar leads
`GET /api/leads`

### Actualizar lead
`PATCH /api/leads/{id}`

### UI interna
`/ops.html`
