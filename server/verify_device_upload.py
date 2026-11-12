"""Verify and optionally clean up a known emulator measurement through the web API."""
import http.cookiejar
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request

values = {}
for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    if "：" in line:
        key, value = line.split("：", 1)
        values[key] = value
item_id = int(sys.argv[2])
base = "https://62.234.140.204"
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
opener.open(base + "/login", urllib.parse.urlencode({"password": values["网页管理密码"]}).encode(), timeout=15).read()
payload = opener.open(base + f"/download/{item_id}", timeout=15).read()
rows = [json.loads(line) for line in payload.decode().splitlines()]
measurement = next(row for row in rows if row["type"] == "sessions")
config = json.loads(measurement["config"])
assert config["device"] == "unknown Android SDK built for x86_64", "Refusing to remove a non-emulator measurement"
assert config["app_version"] == "0.6.0"
samples = [row for row in rows if row["type"] == "samples"]
assert len(samples) == 20
assert {row["direction"] for row in samples} == {"phone_upload", "phone_download"}
assert all("latitude" in row and "nr_ss_sinr_db" in row and "network_type" in row for row in samples)
pathlib.Path(sys.argv[3]).write_bytes(payload)
if "--delete-test" in sys.argv:
    html = opener.open(base + f"/delete/{item_id}", timeout=15).read()
    csrf = re.search(rb'name="csrf" value="([^"]+)"', html).group(1).decode()
    opener.open(base + f"/delete/{item_id}", urllib.parse.urlencode({"csrf": csrf, "confirm": "delete"}).encode(), timeout=15).read()
print("PASS: device upload has 20 bidirectional samples with location, radio and network fields; downloaded successfully")
