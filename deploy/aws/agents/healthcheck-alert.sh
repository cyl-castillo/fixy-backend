#!/usr/bin/env bash
set -euo pipefail

# Disparado por systemd OnFailure= cuando fixy-healthcheck.service exit != 0.
# Lee /var/lib/fixy-monitor/status.env, evita spam (solo notifica si la
# transición fue ok -> fail; estados fail consecutivos no re-notifican)
# y manda un mensaje a Telegram con el detalle.
#
# Env requeridas (desde /etc/fixy-agents.env):
#   TELEGRAM_BOT_TOKEN
#   TELEGRAM_CHAT_ID

STATUS_ENV="${STATUS_ENV:-/var/lib/fixy-monitor/status.env}"
CACHE_FILE="${CACHE_FILE:-/var/lib/fixy-agents/last_alerted_status}"

: "${TELEGRAM_BOT_TOKEN:?missing}"
: "${TELEGRAM_CHAT_ID:?missing}"

if [ ! -r "$STATUS_ENV" ]; then
  exit 0
fi

current_status="$(awk -F= '$1=="status"{gsub(/["\\]/, "", $2); print $2}' "$STATUS_ENV")"
current_message="$(awk -F= '$1=="message"{sub(/^message=/, ""); gsub(/^"|"$/, ""); print}' "$STATUS_ENV")"

if [ -z "$current_status" ] || [ "$current_status" = "ok" ]; then
  echo "$current_status" > "$CACHE_FILE" 2>/dev/null || true
  exit 0
fi

last_status=""
if [ -r "$CACHE_FILE" ]; then
  last_status="$(cat "$CACHE_FILE" 2>/dev/null || true)"
fi

if [ "$last_status" = "$current_status" ]; then
  exit 0
fi

# Mensaje
host="$(hostname -s)"
when="$(date -u +%FT%TZ)"
mem="$(awk -F= '$1=="mem_available_mb"{gsub(/["\\]/, "", $2); print $2}' "$STATUS_ENV")"
disk="$(awk -F= '$1=="disk_root_used_percent"{gsub(/["\\]/, "", $2); print $2}' "$STATUS_ENV")"
backend="$(awk -F= '$1=="service_fixy_backend_service"{gsub(/["\\]/, "", $2); print $2}' "$STATUS_ENV")"
nginx="$(awk -F= '$1=="service_nginx_service"{gsub(/["\\]/, "", $2); print $2}' "$STATUS_ENV")"
cloudflared="$(awk -F= '$1=="service_cloudflared_fixy_service"{gsub(/["\\]/, "", $2); print $2}' "$STATUS_ENV")"

msg="*Fixy ALERTA* — ${host} (${when})
\`\`\`
status: ${current_status}
detalle: ${current_message:-(sin detalle)}

backend: ${backend:-?}
nginx: ${nginx:-?}
cloudflared: ${cloudflared:-?}
mem libre: ${mem:-?} MB
disco /: ${disk:-?}%
\`\`\`"

resp="$(curl -sf --max-time 15 -X POST \
  "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
  --data-urlencode "chat_id=$TELEGRAM_CHAT_ID" \
  --data-urlencode "text=$msg" \
  --data-urlencode "parse_mode=Markdown" \
  --data-urlencode "disable_web_page_preview=true" || true)"

ok="$(echo "$resp" | jq -r '.ok // false' 2>/dev/null || echo false)"
if [ "$ok" = "true" ]; then
  echo "$current_status" > "$CACHE_FILE" 2>/dev/null || true
  echo "[$when] alert sent ($current_status)"
else
  echo "[$when] telegram send failed: $resp" >&2
  exit 1
fi
