#!/usr/bin/env bash
set -euo pipefail
test -f /opt/railband/app.py
install -d -m 700 /var/backups/railband
stamp=$(date +%Y%m%d-%H%M%S)
export RAILBAND_MAP_BACKUP="/var/backups/railband/pre-map-${stamp}.sqlite3"
python3 - <<'PY'
import sqlite3, os
name = os.environ['RAILBAND_MAP_BACKUP']
with sqlite3.connect('/var/lib/railband/archive.sqlite3') as source, sqlite3.connect(name) as target:
    source.backup(target)
os.chmod(name, 0o600)
print('Database backup saved')
PY
backup="/var/backups/railband/code-pre-map-${stamp}.tar.gz"
tar -czf "$backup" --exclude=__pycache__ -C /opt/railband .
tar -xzf /tmp/railband-server-map.tar.gz -C /opt/railband --no-same-owner
python3 -m py_compile /opt/railband/app.py /opt/railband/journeys.py /opt/railband/detail_pages.py /opt/railband/detail_data.py
if ! systemctl restart railband-web || ! curl --retry 6 --retry-connrefused --retry-delay 1 --fail --silent --output /dev/null http://127.0.0.1:8765/login; then
    tar -xzf "$backup" -C /opt/railband --no-same-owner
    systemctl restart railband-web
    echo 'Web update rolled back' >&2
    exit 1
fi
systemctl is-active railband-web nginx rail-iperf
echo "Map detail deployed; code backup: $backup"
