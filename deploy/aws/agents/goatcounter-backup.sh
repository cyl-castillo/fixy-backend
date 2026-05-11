#!/usr/bin/env bash
set -euo pipefail

# Backup diario del SQLite de GoatCounter usando el comando .backup de
# SQLite (atómico, soporta concurrencia con el server en marcha).
#
# Output: /var/backups/goatcounter/db-<TS>.sqlite3 (root:root, 0640).
# Retención: configurable via env, default 14 días.
#
# Env opcional:
#   GC_DB        (default /var/lib/goatcounter/db.sqlite3)
#   BACKUP_DIR   (default /var/backups/goatcounter)
#   RETENTION_DAYS (default 14)

GC_DB="${GC_DB:-/var/lib/goatcounter/db.sqlite3}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/goatcounter}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

if [ ! -r "$GC_DB" ]; then
  echo "[$(date -u +%FT%TZ)] goatcounter db missing: $GC_DB" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"

ts="$(date -u +%Y%m%dT%H%M%SZ)"
out="$BACKUP_DIR/db-${ts}.sqlite3"
tmp="$(mktemp -p "$BACKUP_DIR" db-XXXXXX.sqlite3)"

# .backup es la forma "online" de copiar sin interrumpir al server.
sqlite3 "$GC_DB" ".backup '$tmp'"
mv "$tmp" "$out"
chmod 0640 "$out"

# Retención: borrar dumps más viejos que N días.
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'db-*.sqlite3' -mtime "+$RETENTION_DAYS" -delete

# Output legible
size="$(stat -c '%s' "$out" 2>/dev/null || echo 0)"
echo "[$(date -u +%FT%TZ)] goatcounter backup ok: $out ($size bytes)"
