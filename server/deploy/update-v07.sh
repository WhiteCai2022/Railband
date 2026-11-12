#!/usr/bin/env bash
set -euo pipefail
test -f /opt/railband/app.py
install -d -m 700 /var/backups/railband
python3 - <<'PY'
import sqlite3, time, os
name = '/var/backups/railband/pre-v07-' + str(int(time.time())) + '.sqlite3'
with sqlite3.connect('/var/lib/railband/archive.sqlite3') as source, sqlite3.connect(name) as target:
    source.backup(target)
os.chmod(name, 0o600)
print('Database backup saved')
PY
tar -czf "/var/backups/railband/code-pre-v07-$(date +%s).tar.gz" -C /opt/railband app.py templates static
tar -xzf /tmp/railband-server-v07.tar.gz -C /opt/railband --no-same-owner
python3 -m py_compile /opt/railband/app.py /opt/railband/journeys.py
systemctl restart railband-web
sleep 2
systemctl is-active railband-web nginx rail-iperf
curl --fail --silent --output /dev/null --write-out 'Local login HTTP %{http_code}\n' http://127.0.0.1:8765/login
echo 'Journey API deployed; legacy archive retained'
