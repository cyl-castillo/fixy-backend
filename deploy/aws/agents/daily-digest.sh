#!/usr/bin/env bash
set -euo pipefail

# fixy daily digest: lee leads + healthcheck del backend local y manda
# resumen via Telegram. Diseñado para correr en AWS Lightsail (fixy-prod)
# como systemd timer.
#
# Env requeridas (desde /etc/fixy-agents.env):
#   TELEGRAM_BOT_TOKEN
#   TELEGRAM_CHAT_ID
#   FIXY_OPS_USER          (mismo de fixy.security.username)
#   FIXY_OPS_PASSWORD      (mismo de fixy.security.password)
#
# Env opcionales:
#   API_BASE   (default http://127.0.0.1:8080)
#   STATUS_ENV (default /var/lib/fixy-monitor/status.env)

API_BASE="${API_BASE:-http://127.0.0.1:8080}"
STATUS_ENV="${STATUS_ENV:-/var/lib/fixy-monitor/status.env}"

: "${TELEGRAM_BOT_TOKEN:?missing}"
: "${TELEGRAM_CHAT_ID:?missing}"
: "${FIXY_OPS_USER:?missing}"
: "${FIXY_OPS_PASSWORD:?missing}"

now_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
now_local="$(date '+%Y-%m-%d %H:%M %Z')"

api_curl() {
  curl -sf --max-time 15 --user "$FIXY_OPS_USER:$FIXY_OPS_PASSWORD" "$@"
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

# Bloqueados: blockingFields no vacio o readyForMatching=false
blocked_ids="$(echo "$leads_json" | jq -r '.[] | select(.readyForMatching==false) | "#\(.id)(\(.detectedCategory // "?")|\(.blockingFields | join(",")))"' | head -10 | paste -sd' ' -)"
blocked_count=$(echo "$leads_json" | jq '[.[] | select(.readyForMatching==false)] | length')

# Sin proveedor asignado
unassigned=$(echo "$leads_json" | jq '[.[] | select((.assignedProvider // "") == "")] | length')

# Status nuevos / total
new_count=$(echo "$leads_json" | jq '[.[] | select(.status=="NEW")] | length')

# 4. Armar mensaje (Markdown V2 simple, escape minimo)
msg="*Fixy daily digest* — $now_local
\`\`\`
Leads totales: $total (NEW: $new_count, ult 24h: $last24)
Por categoria: ${by_category:-(sin datos)}
Bloqueados (readyForMatching=false): $blocked_count
Sin proveedor asignado: $unassigned

Healthcheck: $health_status | API publica: ${public_api:-?} | Web: ${public_web:-?}
Memoria libre: ${free_mb} MB | Disco /: $disk_pct
\`\`\`"

if [ -n "$blocked_ids" ]; then
  msg+="
Bloqueados (top 10):
\`$blocked_ids\`"
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
