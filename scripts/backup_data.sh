#!/usr/bin/env bash
set -euo pipefail

ROOT="/home/father/Documents/workspaces/fixy-backend"
DATA_DIR="$ROOT/data"
BACKUP_DIR="$ROOT/backups"
STAMP="$(date +%F-%H%M%S)"
OUT_DIR="$BACKUP_DIR/$STAMP"
LATEST_LINK="$BACKUP_DIR/latest"

mkdir -p "$OUT_DIR"

if [[ ! -d "$DATA_DIR" ]]; then
  echo "[fixy-backup] FAIL: no existe $DATA_DIR"
  exit 1
fi

cp -a "$DATA_DIR/." "$OUT_DIR/"

cat > "$OUT_DIR/README.txt" <<EOF
Fixy backup
created_at=$STAMP
source=$DATA_DIR
host=$(hostname)
EOF

ln -sfn "$OUT_DIR" "$LATEST_LINK"

echo "[fixy-backup] OK: backup creado en $OUT_DIR"
ls -lh "$OUT_DIR"
