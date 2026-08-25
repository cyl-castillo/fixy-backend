#!/usr/bin/env bash
# Inspección READ-ONLY de la config nginx del server de prod.
# No modifica nada: lista sites-enabled/available y muestra el contenido
# de las confs que definen server_name fixy.com.uy / www.fixy.com.uy,
# para poder diffear contra la conf versionada antes de consolidar.
# Mismas vars que deploy.sh / apply-nginx.sh.
set -euo pipefail

SSH_USER="${SSH_USER:-ubuntu}"
SSH_HOST="${SSH_HOST:-52.201.149.5}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/fixy-prod-default.pem}"
SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=accept-new)

ssh "${SSH_OPTS[@]}" "$SSH_USER@$SSH_HOST" '
  echo "=== sites-enabled ==="
  ls -la /etc/nginx/sites-enabled/
  echo
  echo "=== sites-available ==="
  ls -la /etc/nginx/sites-available/
  echo
  for f in /etc/nginx/sites-enabled/*; do
    if grep -lq "fixy.com.uy" "$f" 2>/dev/null; then
      echo "=== contenido de $f (define fixy.com.uy) ==="
      cat "$f"
      echo
    fi
  done
'
