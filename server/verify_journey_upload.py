"""Read and delete only the named synthetic Android journey created by JourneyUploadTest."""
import http.cookiejar
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request

root = Path('D:/NSFC/output/server/journey-test')
item = json.loads((root/'upload-result.json').read_text(encoding='utf-8'))['id']
values = dict(line.split('：',1) for line in Path('D:/NSFC/output/server/credentials.txt').read_text(encoding='utf-8').splitlines() if '：' in line)
base = 'https://62.234.140.204'
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
opener.open(base+'/login', urllib.parse.urlencode({'password':values['网页管理密码']}).encode(), timeout=15).read()
download = opener.open(base+f'/journeys/{item}/download', timeout=20).read()
header, body = download.split(b'\n',1)
assert json.loads(header)['complete'] is True
assert body == (root/'chunk-0.jsonl').read_bytes()+(root/'chunk-1.jsonl').read_bytes()
measurement = json.loads(body.splitlines()[1])
config = json.loads(measurement['config'])
assert config['route'] == 'synthetic journey integration'
assert config['device'] == 'unknown Android SDK built for x86_64'
(root/'server-download.jsonl').write_bytes(download)
html = opener.open(base+f'/journeys/{item}/delete', timeout=15).read()
csrf = re.search(rb'name="csrf" value="([^"]+)"',html).group(1).decode()
opener.open(base+f'/journeys/{item}/delete', urllib.parse.urlencode({'confirm':'delete','csrf':csrf}).encode(), timeout=15).read()
try:
    opener.open(base+f'/journeys/{item}/download', timeout=15)
except urllib.error.HTTPError as exc:
    assert exc.code == 404
else:
    raise AssertionError('Deleted test journey is still downloadable')
print('PASS: authenticated full-journey download matches both Android chunks; only synthetic journey deleted')
