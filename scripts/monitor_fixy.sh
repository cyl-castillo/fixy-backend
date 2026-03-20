#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${FIXY_BASE_URL:-http://127.0.0.1:8080}"
HEALTH_URL="${BASE_URL}/api/health"
STATUS_SCRIPT="/home/father/Documents/workspaces/fixy-backend/scripts/status.sh"
OUT=""
CODE="$(curl -sS -o /tmp/fixy-monitor-health.json -w '%{http_code}' --max-time 8 "$HEALTH_URL" || true)"

if [[ "$CODE" != "200" ]]; then
  echo "FIXY_MONITOR_ALERT: /api/health devolvió HTTP ${CODE:-000}"
  bash "$STATUS_SCRIPT" || true
  exit 1
fi

if ! grep -q '"status"[[:space:]]*:[[:space:]]*"ok"' /tmp/fixy-monitor-health.json; then
  echo "FIXY_MONITOR_ALERT: health respondió 200 pero no reportó status ok"
  cat /tmp/fixy-monitor-health.json
  bash "$STATUS_SCRIPT" || true
  exit 1
fi

if ss -ltnp | grep -q ':38761'; then
  echo "FIXY_MONITOR_ALERT: reapareció listener H2 en :38761"
  bash "$STATUS_SCRIPT" || true
  exit 1
fi

echo "FIXY_MONITOR_OK: backend sano y sin listener H2 extra"
