#!/usr/bin/env bash
set -euo pipefail

# Aplica deploy/aws/nginx-www.fixy.com.uy.conf a AWS Lightsail (fixy-prod).
#
# Idempotente: se puede correr las veces que haga falta. Cada corrida deja
# un backup nuevo con timestamp de la conf que reemplaza, valida con
# `nginx -t` ANTES de tocar nada servible, hace `reload` (no restart, no
# corta conexiones en curso) y si el sitio no responde 200 después del
# reload, revierte sola al backup y vuelve a recargar.
#
# Uso:
#   ./deploy/aws/apply-nginx.sh                # aplica + valida + rollback automatico si falla
#   DRY_RUN=1 ./deploy/aws/apply-nginx.sh       # imprime acciones sin tocar prod
#
# Vars opcionales:
#   SSH_KEY          ruta a la pem (default ~/.ssh/fixy-prod-default.pem)
#   SSH_USER         usuario remoto (default ubuntu)
#   SSH_HOST         host remoto (default 52.201.149.5)
#   LOCAL_CONF       conf versionada a aplicar (default deploy/aws/nginx-www.fixy.com.uy.conf)
#   REMOTE_CONF_NAME nombre del archivo en el server (default www.fixy.com.uy.conf)
#   REMOTE_SITES_AVAILABLE  dir remoto de confs (default /etc/nginx/sites-available)
#   REMOTE_SITES_ENABLED    dir remoto de symlinks activos (default /etc/nginx/sites-enabled)
#   REMOTE_BACKUPS   dir remoto de backups (default /etc/nginx/backups)
#   PUBLIC_URL       url publica para verify (default https://www.fixy.com.uy/)

SSH_KEY="${SSH_KEY:-$HOME/.ssh/fixy-prod-default.pem}"
SSH_USER="${SSH_USER:-ubuntu}"
SSH_HOST="${SSH_HOST:-52.201.149.5}"
LOCAL_CONF="${LOCAL_CONF:-}"
REMOTE_CONF_NAME="${REMOTE_CONF_NAME:-www.fixy.com.uy.conf}"
REMOTE_SITES_AVAILABLE="${REMOTE_SITES_AVAILABLE:-/etc/nginx/sites-available}"
REMOTE_SITES_ENABLED="${REMOTE_SITES_ENABLED:-/etc/nginx/sites-enabled}"
REMOTE_BACKUPS="${REMOTE_BACKUPS:-/etc/nginx/backups}"
PUBLIC_URL="${PUBLIC_URL:-https://www.fixy.com.uy/}"
DRY_RUN="${DRY_RUN:-0}"

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

LOCAL_CONF="${LOCAL_CONF:-$repo_root/deploy/aws/nginx-www.fixy.com.uy.conf}"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
ssh_cmd="ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new $SSH_USER@$SSH_HOST"
remote_conf="$REMOTE_SITES_AVAILABLE/$REMOTE_CONF_NAME"
remote_backup="$REMOTE_BACKUPS/${REMOTE_CONF_NAME}.${ts}.bak"

run_remote() {
  if [ "$DRY_RUN" = "1" ]; then
    echo "[dry-run] ssh: $*"
  else
    $ssh_cmd "$@"
  fi
}

if [ ! -f "$LOCAL_CONF" ]; then
  echo "ERROR: no encuentro $LOCAL_CONF" >&2
  exit 1
fi

echo "==> apply-nginx (ts=$ts) host=$SSH_HOST conf=$remote_conf"

echo "==> asegurando dir de backups remoto"
run_remote "sudo mkdir -p $REMOTE_BACKUPS"

# Si todavia no existe la conf en el server (primer bootstrap), no hay nada
# que respaldar — se documenta en el mensaje pero no es un error.
existed_before="1"
if [ "$DRY_RUN" != "1" ] && ! $ssh_cmd "sudo test -f $remote_conf"; then
  existed_before="0"
fi

if [ "$existed_before" = "1" ]; then
  echo "==> backup remoto -> $remote_backup"
  run_remote "sudo cp $remote_conf $remote_backup"
else
  echo "==> no habia conf previa en $remote_conf (bootstrap), sin backup que hacer"
fi

echo "==> scp conf nueva"
if [ "$DRY_RUN" = "1" ]; then
  echo "[dry-run] scp $LOCAL_CONF -> /tmp/${REMOTE_CONF_NAME}.new"
else
  scp -i "$SSH_KEY" -q "$LOCAL_CONF" "$SSH_USER@$SSH_HOST:/tmp/${REMOTE_CONF_NAME}.new"
fi

echo "==> instalar conf + symlink en sites-enabled"
run_remote "sudo install -o root -g root -m 0644 /tmp/${REMOTE_CONF_NAME}.new $remote_conf \
  && sudo ln -sfn $remote_conf $REMOTE_SITES_ENABLED/$REMOTE_CONF_NAME \
  && rm -f /tmp/${REMOTE_CONF_NAME}.new"

rollback() {
  local reason="$1"
  echo "ERROR: $reason. Iniciando rollback..." >&2
  if [ "$existed_before" != "1" ]; then
    echo "ERROR: no hay backup previo (era bootstrap) — no se puede revertir sola." >&2
    echo "Revisa a mano: $ssh_cmd 'sudo nginx -t'" >&2
    exit 2
  fi
  run_remote "sudo cp $remote_backup $remote_conf && sudo nginx -t && sudo systemctl reload nginx"
  echo "ROLLBACK aplicado a la conf previa. Revisa logs:" >&2
  echo "  $ssh_cmd 'sudo tail -80 /var/log/nginx/error.log'" >&2
  exit 2
}

echo "==> nginx -t (valida ANTES de tocar el proceso en marcha)"
if [ "$DRY_RUN" = "1" ]; then
  echo "[dry-run] nginx -t"
else
  if ! $ssh_cmd "sudo nginx -t" 2>&1; then
    rollback "nginx -t fallo con la conf nueva"
  fi
fi

echo "==> reload nginx (no restart: no corta conexiones en curso)"
run_remote "sudo systemctl reload nginx"

echo "==> verify publico"
if [ "$DRY_RUN" != "1" ]; then
  http_code="$(curl -s -o /dev/null -w '%{http_code}' "$PUBLIC_URL" || echo 000)"
  echo "    HTTP $http_code <- $PUBLIC_URL"
  if [ "$http_code" != "200" ]; then
    rollback "verify publico devolvio $http_code (esperaba 200)"
  fi
fi

if [ "$existed_before" = "1" ]; then
  echo "OK. Backup conservado en $remote_backup"
  echo "Para rollback manual:"
  echo "  $ssh_cmd \"sudo cp $remote_backup $remote_conf && sudo nginx -t && sudo systemctl reload nginx\""
else
  echo "OK. Conf instalada por primera vez en $remote_conf (sin backup previo)."
fi
