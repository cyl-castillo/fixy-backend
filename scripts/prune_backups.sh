#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/father/Documents/workspaces/fixy-backend"
BACKUP_DIR="$ROOT/backups"
KEEP="${FIXY_BACKUP_KEEP:-7}"

mkdir -p "$BACKUP_DIR"

mapfile -t dirs < <(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d | sort)
count="${#dirs[@]}"

if (( count <= KEEP )); then
  echo "[fixy-backup] OK: no hay backups para podar (count=$count keep=$KEEP)"
  exit 0
fi

remove_count=$((count - KEEP))
for ((i=0; i<remove_count; i++)); do
  dir="${dirs[$i]}"
  echo "[fixy-backup] pruning $dir"
  rm -rf -- "$dir"
done

echo "[fixy-backup] OK: backups restantes=$(find "$BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d | wc -l)"
