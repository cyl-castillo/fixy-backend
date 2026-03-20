#!/usr/bin/env bash
set -euo pipefail

say() {
  printf '%s\n' "$1"
}

say '=== fixy-backend service ==='
systemctl status fixy-backend.service --no-pager -l | sed -n '1,25p' || say 'service status unavailable'

say ''
say '=== fixy-backend process ==='
pgrep -af 'fixy-backend-0.0.1-SNAPSHOT.jar' || say 'not running'

say ''
say '=== listening sockets (8080 / 38761) ==='
ss -ltnp | grep -E '(:8080|:38761)' || say 'no matching listeners'

say ''
say '=== health ==='
"$(dirname "$0")/healthcheck.sh" || true

say ''
say '=== recent journal ==='
journalctl -u fixy-backend.service -n 40 --no-pager 2>/dev/null || say 'no journal yet'
