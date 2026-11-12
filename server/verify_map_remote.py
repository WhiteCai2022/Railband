"""Read-only verification of deployed details; prints no credentials/coordinates."""
import http.client
import json
import math
import pathlib
import re
import ssl
import sys
import urllib.parse


def main():
    values = {}
    for line in pathlib.Path(sys.argv[1]).read_text(encoding='utf-8').splitlines():
        if '：' in line:
            key, value = line.split('：', 1)
            values[key] = value
    cookie = ''
    def call(method, path, body=None):
        conn = http.client.HTTPSConnection('62.234.140.204', timeout=60, context=ssl.create_default_context())
        headers = {'Cookie': cookie}
        if body is not None:
            body = urllib.parse.urlencode(body)
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
        conn.request(method, path, body, headers)
        response = conn.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        conn.close()
        return result
    assert call('GET', '/journeys/25/data')[0] == 302, 'detail auth required'
    status, headers, _ = call('POST', '/login', {'password': values['网页管理密码']})
    assert status == 302, status
    cookie = headers['Set-Cookie'].split(';', 1)[0]
    paths = []
    for listing, pattern in [('/', rb'href="(/measurements/\d+)"'), ('/journeys', rb'href="(/journeys/\d+)"')]:
        status, _, html = call('GET', listing)
        assert status == 200, status
        paths.extend(dict.fromkeys(match.decode() for match in re.findall(pattern, html)))
    assert paths, 'no detail links'
    results = []
    for path in paths:
        status, headers, page = call('GET', path)
        assert status == 200 and b'data-url=' in page
        assert headers['Referrer-Policy'] == 'strict-origin-when-cross-origin'
        assert "script-src 'self'" in headers['Content-Security-Policy']
        status, _, body = call('GET', path + '/data')
        assert status == 200, (path, status)
        data = json.loads(body)
        assert data['track']['coordinate_system'] == 'WGS84'
        points = [point for segment in data['track']['segments'] for point in segment]
        assert len(points) == data['track']['display_point_count'] <= 5000
        for point in points:
            assert math.isfinite(point['lat']) and -90 <= point['lat'] <= 90
            assert math.isfinite(point['lon']) and -180 <= point['lon'] <= 180
        results.append({'path': path, 'samples': data['summary']['sample_count'],
            'fixes': data['track']['fix_count'], 'display_points': len(points),
            'segments': len(data['track']['segments']), 'complete': data['item']['complete']})
    for asset in ['/static/detail.js', '/static/detail.css', '/static/vendor/leaflet/leaflet.js', '/static/vendor/leaflet/leaflet.css']:
        assert call('GET', asset)[0] == 200, asset
    print(json.dumps({'status': 'PASS', 'records_checked': len(results), 'records': results}, ensure_ascii=False))
    if len(sys.argv) > 2:
        pathlib.Path(sys.argv[2]).write_text(json.dumps({'status':'PASS', 'records':results}, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
