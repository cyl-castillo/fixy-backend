# Fixy Backend Deployment Notes

## Estado actual

- Backend local corriendo en `127.0.0.1:8080`
- Sin UI propia (Refundación fase 1, contrato §7): `/` responde JSON simple, la UI operativa vive en el admin de `fixy-app`
- Refundación fase 2 (2026-09-15): el modelo de cobro pasa de comisión al técnico a cargo de servicio al cliente. `fixy.payments.provider-commission-enabled` (antes `fixy.payments.enabled`) default **false** en prod desde este deploy — deja de crearse `LeadPayment` para trabajos nuevos (lo histórico sigue visible en "Comisiones"). `fixy.orders.service-fee-enabled` default **true** — Mercado Pago le cobra al vecino, no al técnico. Ver `deploy/aws/fixy-backend.env.example` para las variables nuevas y las tres que reemplazan a `fixy.stale-matching.*` / `fixy.matching.auto-release.*` / `fixy.orphan-match-retry.*` (unificadas en `fixy.matching.watchdog.*`, `MatchingWatchdogScheduler`).
- Migración `V29__customer_payments_remote_care.sql` corre sola contra Postgres (Flyway) — agrega `customer_payments`, `remote_care_plans`, `lead_ratings.verified`, `leads.remote_care_plan_id` y siembra el servicio de catálogo `care_visita` (precio 0, visita preventiva del plan Casa a distancia).
- Tier 1 (contrato `TIER1_CONTRATO.md`, 2026-09-16): protocolo "al llegar" (§B, Decreto 244/000 art. 9) y cargo de servicio decidido al reservar (§C). Migración `V30__price_change_protocol_and_service_fee_opt_out.sql` agrega a `leads`: `proposed_amount/proposed_reason/proposed_at` (propuesta del proveedor), `agreed_amount/agreed_at` (aceptación del cliente, registro legal), `price_change_rejected_at` (desvío del contrato — necesaria para distinguir REJECTED de PENDING, ver el archivo de contrato) y `service_fee_opt_out` (el cliente eligió "solo el técnico, sin garantía" al reservar). Endpoints nuevos: `POST /api/public/providers/{id}/leads/{leadId}/price-change`, `POST /api/public/leads/{id}/price-change/accept`, `POST /api/public/leads/{id}/price-change/reject`. `POST /api/public/orders` acepta `serviceFeeOptOut` (default false). El recordatorio único de `CustomerPaymentReminderScheduler` pasa de 48h a 24h. `SitemapService` quita la entrada estática `/ofertas` (pausado) y agrega `/terminos` y las tres páginas `/servicios/...`.
- nginx (Tier 1 §A, mismo deploy): `deploy/aws/nginx-www.fixy.com.uy.conf` agrega redirect 301 del apex `fixy.com.uy` → `www` conservando path y query, `error_page 404 /404.html` (404 real para rutas desconocidas; antes toda ruta devolvía 200 con la SPA), `location /servicios/` sirviendo las páginas estáticas generadas por `fixy-app/scripts/build-seo-pages.mjs` con cache de 1h, y una lista explícita de rutas SPA (`/c/`, `/p/`, `/consulta/`, `/mi-casa/`, `/panel`, `/admin`, `/terminos`, etc.) que hacen `try_files /index.html`. **Orden obligatorio:** deploy del frontend primero (la release tiene que contener `/404.html` y `/servicios/*`), después `./deploy/aws/apply-nginx.sh` (hace `nginx -t`, backup y reload; rollback automático si falla). Smoke: `/servicios/aire-acondicionado-ciudad-de-la-costa/` 200, `/ruta-inexistente` 404, `/terminos` 200.
- Tier 2 (contrato `TIER2_CONTRATO.md`, 2026-09-18): ventanas de disponibilidad + rotación por respuesta relativa (§A), asignación con nombre/foto/franja + hora límite de búsqueda (§B), reseña verificada a las 24h + detractores a un humano + respuesta pública (§C). Migración `V31__provider_availability_offers_photo_reviews.sql` agrega `providers.availability_windows` (string plano `lun=08-20;...`, ver `AvailabilityWindows`), `providers.photo_url`, tabla `provider_offers` (registro uno-a-uno de cada oferta: contexto, si nació en ventana, cuándo/cómo respondió — fuente del score de respuesta), `leads.arrival_window`, `leads.search_deadline_at`, `lead_ratings.provider_reply`/`provider_reply_at`. Ranking (`ProviderCatalogService.findMatches`) ahora ordena zona → ventana ahora → mediana de minutos de respuesta (últimas 10 ofertas en ventana, prior 30 min con <3 muestras, tope 240) → rating. Endpoints nuevos: `PATCH /api/public/providers/{id}/availability` acepta `availabilityWindows` (antes solo `acceptingWork`); `POST`/`DELETE /api/public/providers/{id}/photo`; `POST /api/public/leads/{id}/time-window`; `POST /api/public/leads/{id}/rating`; `POST /api/public/providers/{id}/leads/{leadId}/rating-reply`. `POST /opportunities/{leadId}/accept` y `POST /leads/{leadId}/status` (ASSIGNED) aceptan `arrivalWindow` opcional. Nuevos schedulers: `ReviewRequestScheduler` (`fixy.reviews.request.*`, pedido de reseña a las 24h del pago o del COMPLETED) y dos frentes nuevos en `MatchingWatchdogScheduler` (hora límite vencida, "pedido mudo" ≥4h sin mensaje visible — solo avisa a ops). `fixy.matching.response.*` y `fixy.matching.search-deadline-minutes` son variables nuevas — ver `deploy/aws/fixy-backend.env.example`.
- Protección básica con HTTP Basic Auth sobre `/api/leads/**`, `/api/providers/**`, `/api/offers/**`, `/api/services/**`
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
- `http://127.0.0.1:8080/` (JSON simple, sin UI propia)

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
Retirada (Refundación fase 1, contrato §7) — la UI operativa vive en el admin de `fixy-app`, este backend solo expone API.
