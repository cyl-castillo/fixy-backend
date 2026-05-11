#!/usr/bin/env bash
set -euo pipefail

# Instala/actualiza el digest en AWS Lightsail (fixy-prod).
# Idempotente. Requiere /etc/fixy-agents.env ya creado en el server con
# valores reales (ver fixy-agents.env.example).
#
# Uso:
#   ./install.sh                # rsync + reload + enable + run-test
#   DRY_RUN=1 ./install.sh

SSH_KEY="${SSH_KEY:-$HOME/Downloads/LightsailDefaultKey-us-east-1.pem}"
SSH_USER="${SSH_USER:-ubuntu}"
SSH_HOST="${SSH_HOST:-52.201.149.5}"
DRY_RUN="${DRY_RUN:-0}"

here="$(cd "$(dirname "$0")" && pwd)"
ssh_cmd="ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new $SSH_USER@$SSH_HOST"

run_remote() {
  if [ "$DRY_RUN" = "1" ]; then
    echo "[dry-run] ssh: $*"
  else
    $ssh_cmd "$@"
  fi
}

echo "==> rsync scripts -> /tmp/fixy-agents-stage"
if [ "$DRY_RUN" = "1" ]; then
  echo "[dry-run] rsync $here/ -> /tmp/fixy-agents-stage/"
else
  rsync -az -e "ssh -i $SSH_KEY" \
    --exclude '*.example' --exclude install.sh \
    "$here/" "$SSH_USER@$SSH_HOST:/tmp/fixy-agents-stage/"
fi

echo "==> verificar /etc/fixy-agents.env"
run_remote "test -f /etc/fixy-agents.env || (echo 'ERROR: /etc/fixy-agents.env no existe en el server. Crear primero (ver fixy-agents.env.example).' >&2; exit 1)"

echo "==> instalar binarios y units"
run_remote "sudo install -d -o fixy -g fixy -m 0755 /opt/fixy-agents \
  && sudo install -d -o fixy -g fixy -m 0755 /var/lib/fixy-agents \
  && sudo install -d -o root -g root -m 0750 /var/backups/goatcounter \
  && sudo install -o fixy -g fixy -m 0755 /tmp/fixy-agents-stage/daily-digest.sh /opt/fixy-agents/daily-digest.sh \
  && sudo install -o fixy -g fixy -m 0755 /tmp/fixy-agents-stage/healthcheck-alert.sh /opt/fixy-agents/healthcheck-alert.sh \
  && sudo install -o root -g root -m 0755 /tmp/fixy-agents-stage/goatcounter-backup.sh /opt/fixy-agents/goatcounter-backup.sh \
  && sudo install -o root -g root -m 0644 /tmp/fixy-agents-stage/fixy-daily-digest.service /etc/systemd/system/fixy-daily-digest.service \
  && sudo install -o root -g root -m 0644 /tmp/fixy-agents-stage/fixy-daily-digest.timer /etc/systemd/system/fixy-daily-digest.timer \
  && sudo install -o root -g root -m 0644 /tmp/fixy-agents-stage/fixy-healthcheck-alert.service /etc/systemd/system/fixy-healthcheck-alert.service \
  && sudo install -o root -g root -m 0644 /tmp/fixy-agents-stage/goatcounter-backup.service /etc/systemd/system/goatcounter-backup.service \
  && sudo install -o root -g root -m 0644 /tmp/fixy-agents-stage/goatcounter-backup.timer /etc/systemd/system/goatcounter-backup.timer \
  && sudo touch /var/log/fixy-agents.log \
  && sudo chown fixy:fixy /var/log/fixy-agents.log \
  && sudo chmod 0640 /etc/fixy-agents.env \
  && sudo chown root:fixy /etc/fixy-agents.env \
  && rm -rf /tmp/fixy-agents-stage"

echo "==> wire OnFailure + OnSuccess del healthcheck (drop-in)"
run_remote "sudo install -d -m 0755 /etc/systemd/system/fixy-healthcheck.service.d \
  && printf '%s\\n' '[Unit]' 'OnFailure=fixy-healthcheck-alert.service' 'OnSuccess=fixy-healthcheck-alert.service' | sudo tee /etc/systemd/system/fixy-healthcheck.service.d/alert.conf >/dev/null"

echo "==> reload systemd + enable timers"
run_remote "sudo systemctl daemon-reload && sudo systemctl enable --now fixy-daily-digest.timer goatcounter-backup.timer"

echo "==> verificar timer"
run_remote "systemctl list-timers fixy-daily-digest.timer --no-pager"

echo "==> ejecutar digest una vez (smoke test)"
run_remote "sudo systemctl start fixy-daily-digest.service && sleep 3 && tail -20 /var/log/fixy-agents.log"

echo "OK. Verificá Telegram para confirmar que llegó el mensaje de prueba."
echo "Para forzar otra ejecucion manual:"
echo "  $ssh_cmd 'sudo systemctl start fixy-daily-digest.service && tail -f /var/log/fixy-agents.log'"
