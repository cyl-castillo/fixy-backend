#!/usr/bin/env bash
set -euo pipefail

# fixy daily digest: lee leads + healthcheck del backend local y manda
# resumen via Telegram. Diseñado para correr en AWS Lightsail (fixy-prod)
# como systemd timer.
#
# Env requeridas (desde /etc/fixy-agents.env):
#   TELEGRAM_BOT_TOKEN
#   TELEGRAM_CHAT_ID
#   FIXY_OPS_USERNAME          (mismo de fixy.security.username)
#   FIXY_OPS_PASSWORD      (mismo de fixy.security.password)
#
# Env opcionales:
#   API_BASE   (default http://127.0.0.1:8080)
#   STATUS_ENV (default /var/lib/fixy-monitor/status.env)

API_BASE="${API_BASE:-http://127.0.0.1:8080}"
STATUS_ENV="${STATUS_ENV:-/var/lib/fixy-monitor/status.env}"

: "${TELEGRAM_BOT_TOKEN:?missing}"
: "${TELEGRAM_CHAT_ID:?missing}"
: "${FIXY_OPS_USERNAME:?missing}"
: "${FIXY_OPS_PASSWORD:?missing}"

now_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
now_local="$(date '+%Y-%m-%d %H:%M %Z')"

api_curl() {
  curl -sf --max-time 15 --user "$FIXY_OPS_USERNAME:$FIXY_OPS_PASSWORD" "$@"
}

# 1. Healthcheck rapido
health="$(curl -sf --max-time 5 "$API_BASE/api/health" 2>/dev/null || echo '{"status":"unreachable"}')"
health_status="$(echo "$health" | jq -r '.status // "unknown"')"

# 2. status.env del monitor (si existe)
mem_mb=""
disk_pct=""
public_api=""
public_web=""
if [ -r "$STATUS_ENV" ]; then
  # shellcheck disable=SC1090
  source <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$STATUS_ENV" | sed 's/=\\\(.*\\\)$/=\1/')
  mem_mb="${mem_available_mb:-}"
  public_api="${public_api_http_code:-}"
  public_web="${public_web_http_code:-}"
fi
disk_pct="$(df -h / | awk 'NR==2 {print $5}')"
free_mb="$(free -m | awk '/^Mem:/ {print $7}')"

# 3. Listar leads
leads_json="$(api_curl "$API_BASE/api/leads" || echo '[]')"
total=$(echo "$leads_json" | jq 'length')

# Distribucion por categoria
by_category="$(echo "$leads_json" | jq -r 'group_by(.detectedCategory // "sin-categoria") | map("\(.[0].detectedCategory // "sin-categoria")=\(length)") | join(", ")')"

# Leads creados ultimas 24h (createdAt > now-24h)
since_iso="$(date -u -d '24 hours ago' +%Y-%m-%dT%H:%M:%S)"
last24=$(echo "$leads_json" | jq --arg since "$since_iso" '[.[] | select(.createdAt >= $since)] | length')

# Bloqueados: readyForMatching=false y todavía vivos. Los CANCELLED/COMPLETED
# se excluyen — un lead cerrado no es demanda trabada, y contarlos infló el
# número histórico hasta 80 cuando los accionables eran 5 (hallazgo 2026-07-21).
blocked_ids="$(echo "$leads_json" | jq -r '.[] | select(.readyForMatching==false and .status!="CANCELLED" and .status!="COMPLETED") | "#\(.id)(\(.detectedCategory // "?")|\(.blockingFields | join(",")))"' | head -10 | paste -sd' ' -)"
blocked_count=$(echo "$leads_json" | jq '[.[] | select(.readyForMatching==false and .status!="CANCELLED" and .status!="COMPLETED")] | length')

# Sin proveedor asignado
unassigned=$(echo "$leads_json" | jq '[.[] | select((.assignedProvider // "") == "")] | length')

# Status nuevos / total
new_count=$(echo "$leads_json" | jq '[.[] | select(.status=="NEW")] | length')

# 3b. Métricas de negocio (P0-3, H3.1-H3.3) de los últimos 7 días.
# NO se activa/programa como parte de esta épica (H3.4 solo deja el digest
# preparado). Si falla, no debe romper el resto del digest.
metrics_json="$(api_curl "$API_BASE/api/ops/metrics/daily" || echo '{}')"
fill_rate="$(echo "$metrics_json" | jq -r 'if .fillRatePercentage then (.fillRatePercentage | tostring) else "?" end')"
median_response_s="$(echo "$metrics_json" | jq -r '.medianTimeToFirstResponseSeconds // "sin datos"')"
repeat_rate="$(echo "$metrics_json" | jq -r 'if .repeatRateAutodeclaredPercentage then (.repeatRateAutodeclaredPercentage | tostring) else "?" end')"
metrics_total="$(echo "$metrics_json" | jq -r '.totalLeadsCreated // "?"')"

# Coverage gaps: (categoria, zona) sin proveedores activos.
CATEGORIES="plomeria jardineria barometrica aires_acondicionados"
ZONES="Solymar Lagomar El%20Pinar Shangrilá Barra%20de%20Carrasco Ciudad%20de%20la%20Costa"
coverage_gaps=""
for cat in $CATEGORIES; do
  for zone in $ZONES; do
    zone_decoded="$(printf '%b' "${zone//%/\\x}")"
    preview="$(curl -sf --max-time 5 "$API_BASE/api/public/providers/preview?category=$cat&zone=$zone&limit=0" 2>/dev/null || echo '{"count":0}')"
    cnt="$(echo "$preview" | jq -r '.count // 0')"
    if [ "$cnt" = "0" ]; then
      coverage_gaps+="$cat / $zone_decoded; "
    fi
  done
done

# 4. Armar mensaje (Markdown V2 simple, escape minimo)
msg="*Fixy daily digest* — $now_local
\`\`\`
Leads totales: $total (NEW: $new_count, ult 24h: $last24)
Por categoria: ${by_category:-(sin datos)}
Bloqueados (readyForMatching=false): $blocked_count
Sin proveedor asignado: $unassigned

Healthcheck: $health_status | API publica: ${public_api:-?} | Web: ${public_web:-?}
Memoria libre: ${free_mb} MB | Disco /: $disk_pct

Métricas de negocio (últimos 7 días, $metrics_total leads):
  Fill rate: ${fill_rate}%
  Mediana 1a respuesta proveedor: ${median_response_s}s
  Repeat rate (autodeclarado): ${repeat_rate}%
\`\`\`"

if [ -n "$blocked_ids" ]; then
  msg+="
Bloqueados (top 10):
\`$blocked_ids\`"
fi

if [ -n "$coverage_gaps" ]; then
  msg+="
Sin cobertura:
\`${coverage_gaps%; }\`"
fi

# 5. Enviar a Telegram
resp="$(curl -sf --max-time 15 -X POST \
  "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
  --data-urlencode "chat_id=$TELEGRAM_CHAT_ID" \
  --data-urlencode "text=$msg" \
  --data-urlencode "parse_mode=Markdown" \
  --data-urlencode "disable_web_page_preview=true" || true)"

ok="$(echo "$resp" | jq -r '.ok // false')"
if [ "$ok" != "true" ]; then
  echo "[$(date -u +%FT%TZ)] telegram send failed: $resp" >&2
  exit 1
fi

echo "[$(date -u +%FT%TZ)] digest sent ok (total=$total blocked=$blocked_count)"
