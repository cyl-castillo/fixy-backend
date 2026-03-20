#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${FIXY_BASE_URL:-http://127.0.0.1:8080}"
TIMEOUT="${FIXY_TIMEOUT:-5}"

say() {
  printf '%s\n' "$1"
}

say "[fixy] healthcheck against ${BASE_URL}"

HTTP_CODE="$(curl -sS -o /tmp/fixy-health.json -w '%{http_code}' --max-time "$TIMEOUT" "${BASE_URL}/api/health" || true)"

if [[ "$HTTP_CODE" != "200" ]]; then
  say "[fixy] FAIL: /api/health returned HTTP ${HTTP_CODE:-000}"
  exit 1
fi

if grep -q '"status"[[:space:]]*:[[:space:]]*"ok"' /tmp/fixy-health.json; then
  say "[fixy] OK: backend healthy"
  cat /tmp/fixy-health.json
  exit 0
fi

say "[fixy] FAIL: health endpoint did not report status ok"
cat /tmp/fixy-health.json
exit 1
