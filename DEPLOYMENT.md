# Fixy Backend Deployment Notes

## Estado actual

- Backend local corriendo en `127.0.0.1:8080`
- UI interna mínima en `/ops.html`
- Protección básica con HTTP Basic Auth
- Exposición temporal por Cloudflare quick tunnel

## Credenciales actuales de ops

- Usuario: `fixy`
- Password: `fixy-ops-2026`

> Cambiar estas credenciales antes de un despliegue más serio.

## URLs actuales

### Local
- `http://127.0.0.1:8080/api/health`
- `http://127.0.0.1:8080/ops.html`

### Pública temporal
- Quick tunnel de Cloudflare (puede cambiar o caer)

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

1. Mover usuario/password de ops a variables de entorno
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
