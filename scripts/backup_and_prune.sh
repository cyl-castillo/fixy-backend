#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/father/Documents/workspaces/fixy-backend"
cd "$ROOT"

bash scripts/backup_data.sh
bash scripts/prune_backups.sh
