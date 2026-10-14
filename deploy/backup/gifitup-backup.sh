#!/usr/bin/env bash
# Nightly Postgres dump for GifItUp, run by gifitup-backup.timer as the deploy user (docs/ops/vm-setup.md).
# Dumps stay on the VM for now (owner decision, 2026-10-14).
set -euo pipefail

backup_dir=${BACKUP_DIR:-/var/backups/gifitup}
keep_days=${BACKUP_KEEP_DAYS:-14}
compose_dir=${COMPOSE_DIR:-/opt/gifitup/deploy}

umask 077
out="$backup_dir/gifitup-$(date -u +%Y%m%dT%H%M%SZ).dump"
trap 'rm -f "$out.partial"' EXIT

docker compose --project-directory "$compose_dir" exec -T postgres \
  pg_dump --username=gifitup --dbname=gifitup --format=custom >"$out.partial"
mv "$out.partial" "$out"

find "$backup_dir" -maxdepth 1 -name 'gifitup-*.dump' -mtime +"$keep_days" -delete
echo "Backup written: $out ($(stat -c %s "$out") bytes)"
