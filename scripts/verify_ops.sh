#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${FIXY_BASE_URL:-http://127.0.0.1:8080}"
OPS_USER="${FIXY_OPS_USERNAME:-}"
OPS_PASS="${FIXY_OPS_PASSWORD:-}"

say() {
  printf '%s\n' "$1"
}

say "[fixy] verificacion operativa contra ${BASE_URL}"

PUBLIC_CODE="$(curl -sS -o /tmp/fixy-public-health.json -w '%{http_code}' --max-time 5 "${BASE_URL}/api/health" || true)"
if [[ "$PUBLIC_CODE" != "200" ]]; then
  say "[fixy] FAIL: /api/health devolvio HTTP ${PUBLIC_CODE:-000}"
  exit 1
fi
say "[fixy] OK: /api/health publico"

# ops.html se retiro (Refundacion fase 1, contrato §7): la UI operativa
# ahora es el admin del frontend, no una pagina servida por el backend.
# Verificamos en su lugar que un endpoint admin real siga protegido.
SERVICES_CODE="$(curl -sS -o /tmp/fixy-services.json -w '%{http_code}' --max-time 5 "${BASE_URL}/api/services" || true)"
if [[ "$SERVICES_CODE" != "401" ]]; then
  say "[fixy] FAIL: /api/services deberia pedir auth y devolvio HTTP ${SERVICES_CODE:-000}"
  exit 1
fi
say "[fixy] OK: /api/services protegido"

LEADS_CODE="$(curl -sS -o /tmp/fixy-leads.json -w '%{http_code}' --max-time 5 "${BASE_URL}/api/leads" || true)"
if [[ "$LEADS_CODE" != "401" ]]; then
  say "[fixy] FAIL: /api/leads deberia pedir auth y devolvio HTTP ${LEADS_CODE:-000}"
  exit 1
fi
say "[fixy] OK: /api/leads protegido"

if [[ -n "$OPS_USER" && -n "$OPS_PASS" ]]; then
  AUTH_CODE="$(curl -sS -u "$OPS_USER:$OPS_PASS" -o /tmp/fixy-auth-leads.json -w '%{http_code}' --max-time 5 "${BASE_URL}/api/leads" || true)"
  if [[ "$AUTH_CODE" != "200" ]]; then
    say "[fixy] FAIL: credenciales ops presentes pero /api/leads autenticado devolvio HTTP ${AUTH_CODE:-000}"
    exit 1
  fi
  say "[fixy] OK: auth ops funcional"
else
  say "[fixy] WARN: FIXY_OPS_USERNAME/PASSWORD no estan cargadas en este shell; auth positiva no verificada"
fi

say "[fixy] OK: verificacion operativa minima completa"
