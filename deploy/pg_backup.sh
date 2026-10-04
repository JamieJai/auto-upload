#!/bin/bash
# auto-db 의 PostgreSQL 을 auto-app 의 HDD(/data/backup/pg)로 덤프한다. 14일 보관.
# systemd: autoreg-pg-backup.timer (매일 03:30) 가 실행한다.
set -euo pipefail
set -a; . /opt/autoreg/.env; set +a
OUT=/data/backup/pg/autoreg-$(date +%Y%m%d-%H%M).dump
docker run --rm -e PGPASSWORD="$DB_PASSWORD" -v /data/backup/pg:/data/backup/pg postgres:16 \
  pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -Fc -f "$OUT"
find /data/backup/pg -name 'autoreg-*.dump' -mtime +14 -delete
echo "$(date -Is) ok $OUT $(stat -c %s "$OUT")"
