#!/usr/bin/env bash
set -euo pipefail

# Deploy fixy-backend a AWS Lightsail (fixy-prod).
#
# Uso:
#   ./deploy/aws/deploy.sh                 # build + deploy + verify + rollback automatico si falla
#   SKIP_BUILD=1 ./deploy/aws/deploy.sh    # asume target/*.jar ya construido
#   DRY_RUN=1 ./deploy/aws/deploy.sh       # imprime acciones sin tocar prod
#
# Vars opcionales:
#   SSH_KEY     ruta a la pem (default ~/Downloads/LightsailDefaultKey-us-east-1.pem)
#   SSH_USER    usuario remoto (default ubuntu)
#   SSH_HOST    host remoto (default 52.201.149.5)
#   REMOTE_JAR  destino (default /opt/fixy-backend/fixy-backend.jar)
#   SERVICE     systemd unit (default fixy-backend)
#   HEALTH_URL  url local en el server (default http://127.0.0.1:8080/api/health)

SSH_KEY="${SSH_KEY:-$HOME/Downloads/LightsailDefaultKey-us-east-1.pem}"
SSH_USER="${SSH_USER:-ubuntu}"
SSH_HOST="${SSH_HOST:-52.201.149.5}"
REMOTE_JAR="${REMOTE_JAR:-/opt/fixy-backend/fixy-backend.jar}"
REMOTE_BACKUPS="${REMOTE_BACKUPS:-/opt/fixy-backend/backups}"
SERVICE="${SERVICE:-fixy-backend}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/api/health}"
SKIP_BUILD="${SKIP_BUILD:-0}"
DRY_RUN="${DRY_RUN:-0}"

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

local_jar="$repo_root/target/fixy-backend-0.0.1-SNAPSHOT.jar"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
ssh_cmd="ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new $SSH_USER@$SSH_HOST"

run_remote() {
  if [ "$DRY_RUN" = "1" ]; then
    echo "[dry-run] ssh: $*"
  else
    $ssh_cmd "$@"
  fi
}

echo "==> deploy fixy-backend (ts=$ts) host=$SSH_HOST service=$SERVICE"

if [ "$SKIP_BUILD" != "1" ]; then
  echo "==> build (mvn package, skipping tests)"
  mvn -q -o package -DskipTests
fi

if [ ! -f "$local_jar" ]; then
  echo "ERROR: no encuentro $local_jar" >&2
  exit 1
fi

echo "==> backup remoto -> $REMOTE_BACKUPS/fixy-backend-${ts}.jar"
run_remote "sudo cp $REMOTE_JAR $REMOTE_BACKUPS/fixy-backend-${ts}.jar"

echo "==> scp jar nuevo"
if [ "$DRY_RUN" = "1" ]; then
  echo "[dry-run] scp $local_jar -> /tmp/fixy-backend-new.jar"
else
  scp -i "$SSH_KEY" -q "$local_jar" "$SSH_USER@$SSH_HOST:/tmp/fixy-backend-new.jar"
fi

echo "==> swap + restart"
run_remote "sudo install -o fixy -g fixy -m 0644 /tmp/fixy-backend-new.jar $REMOTE_JAR && sudo systemctl restart $SERVICE && rm /tmp/fixy-backend-new.jar"

echo "==> esperando healthcheck"
healthy=0
for i in $(seq 1 24); do
  if [ "$DRY_RUN" = "1" ]; then
    healthy=1
    break
  fi
  if $ssh_cmd "curl -sf $HEALTH_URL >/dev/null"; then
    healthy=1
    echo "    healthy en intento $i"
    break
  fi
  sleep 2
done

if [ "$healthy" != "1" ]; then
  echo "ERROR: healthcheck fallo. Iniciando rollback..." >&2
  run_remote "sudo install -o fixy -g fixy -m 0644 $REMOTE_BACKUPS/fixy-backend-${ts}.jar $REMOTE_JAR && sudo systemctl restart $SERVICE"
  echo "ROLLBACK aplicado al jar previo. Revisa logs:" >&2
  echo "  $ssh_cmd 'tail -80 /var/log/fixy-backend.log'" >&2
  exit 2
fi

echo "==> verify publico"
if [ "$DRY_RUN" != "1" ]; then
  curl -sf https://api.fixy.com.uy/api/health && echo
fi

echo "OK. Backup conservado en $REMOTE_BACKUPS/fixy-backend-${ts}.jar"
echo "Para rollback manual:"
echo "  $ssh_cmd \"sudo install -o fixy -g fixy -m 0644 $REMOTE_BACKUPS/fixy-backend-${ts}.jar $REMOTE_JAR && sudo systemctl restart $SERVICE\""
