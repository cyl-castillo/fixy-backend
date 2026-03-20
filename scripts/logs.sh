#!/usr/bin/env bash
set -euo pipefail

LINES="${1:-80}"

journalctl -u fixy-backend.service -n "$LINES" --no-pager
