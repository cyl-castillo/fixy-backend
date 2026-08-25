#!/usr/bin/env bash
# Consolidación one-shot (2026-08-25): el sitio www vivía en fixy-web.conf
# (hecho a mano) y la conf versionada www.fixy.com.uy.conf quedaba ignorada
# por "conflicting server name" (nginx se queda con la primera por orden
# alfabético de sites-enabled: f < w). Este script deja UNA sola fuente:
#   1. re-aplica la conf versionada (que ya absorbió el bloque /stats/
#      que solo existía a mano) vía apply-nginx.sh,
#   2. deshabilita fixy-web.conf (saca el symlink de sites-enabled; el
#      archivo queda en sites-available como referencia histórica),
#   3. nginx -t + reload + verify; si algo falla, re-linkea fixy-web.conf
#      y recarga (rollback al estado actual que se sabe que funciona).
set -euo pipefail

SSH_USER="${SSH_USER:-ubuntu}"
SSH_HOST="${SSH_HOST:-52.201.149.5}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/fixy-prod-default.pem}"
SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=accept-new)
DRY_RUN="${DRY_RUN:-0}"

run_ssh() {
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[dry-run] ssh: $*"
  else
    ssh "${SSH_OPTS[@]}" "$SSH_USER@$SSH_HOST" "$@"
  fi
}

echo "==> paso 1: re-aplicar conf versionada (con /stats/) via apply-nginx.sh"
DRY_RUN="$DRY_RUN" SSH_KEY="$SSH_KEY" SSH_USER="$SSH_USER" SSH_HOST="$SSH_HOST" \
  "$(dirname "$0")/apply-nginx.sh"

echo "==> paso 2: deshabilitar fixy-web.conf (symlink fuera de sites-enabled)"
run_ssh 'sudo rm -f /etc/nginx/sites-enabled/fixy-web.conf'

echo "==> paso 3: nginx -t + reload"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "[dry-run] nginx -t && reload"
else
  if ! ssh "${SSH_OPTS[@]}" "$SSH_USER@$SSH_HOST" 'sudo nginx -t'; then
    echo "!! nginx -t falló — rollback: re-link fixy-web.conf" >&2
    ssh "${SSH_OPTS[@]}" "$SSH_USER@$SSH_HOST" \
      'sudo ln -sfn /etc/nginx/sites-available/fixy-web.conf /etc/nginx/sites-enabled/fixy-web.conf && sudo nginx -t && sudo systemctl reload nginx'
    exit 1
  fi
  ssh "${SSH_OPTS[@]}" "$SSH_USER@$SSH_HOST" 'sudo systemctl reload nginx'
fi

echo "==> verify publico"
if [[ "$DRY_RUN" == "1" ]]; then
  echo "[dry-run] curl verifies"
  exit 0
fi
ok=1
code_web=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 https://www.fixy.com.uy/) || ok=0
ct_sitemap=$(curl -s -o /dev/null -w '%{content_type}' --max-time 15 https://www.fixy.com.uy/sitemap.xml) || ok=0
code_stats=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 https://www.fixy.com.uy/stats/count) || ok=0
echo "    web=$code_web sitemap_ct=$ct_sitemap stats=$code_stats"
if [[ "$ok" != "1" || "$code_web" != "200" || "$ct_sitemap" != *xml* ]]; then
  echo "!! verify falló — rollback: re-link fixy-web.conf" >&2
  ssh "${SSH_OPTS[@]}" "$SSH_USER@$SSH_HOST" \
    'sudo ln -sfn /etc/nginx/sites-available/fixy-web.conf /etc/nginx/sites-enabled/fixy-web.conf && sudo nginx -t && sudo systemctl reload nginx'
  exit 1
fi
echo "OK. www servido SOLO por la conf versionada www.fixy.com.uy.conf."
echo "Para rollback manual:"
echo "  ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new $SSH_USER@$SSH_HOST \"sudo ln -sfn /etc/nginx/sites-available/fixy-web.conf /etc/nginx/sites-enabled/fixy-web.conf && sudo nginx -t && sudo systemctl reload nginx\""
