"""Local-only synthetic map preview. Never imports production credentials/data."""
import json
import tempfile
from pathlib import Path
from app import create_app, hash_secret


def main():
    with tempfile.TemporaryDirectory(prefix='railband-map-preview-') as temp:
        app = create_app({'SECRET_KEY': 'local-preview-only', 'ADMIN_HASH': hash_secret('local-map-preview'),
            'UPLOAD_HASH': hash_secret('local-preview-upload'), 'DATABASE': str(Path(temp) / 'preview.sqlite3'),
            'SESSION_COOKIE_SECURE': False})
        start = 1791964800000
        trip = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
        config = {'journey_id': trip, 'route': '北京南 → 天津 · 地图预览', 'device': '模拟路线（仅本地）',
            'direction': 'bidirectional', 'sample_interval_s': 20, 'rate_cap_bps': 5000000, 'app_version': '0.7.1'}
        records = [
            {'type': 'metadata', 'schema_version': 5, 'format': 'railband_journey_chunk', 'journey_id': trip, 'chunk_index': 0, 'is_final': True},
            {'type': 'sessions', 'id': 1, 'journey_id': trip, 'started_utc_ms': start,
             'ended_utc_ms': start + 2400000, 'status': 'completed', 'config': json.dumps(config, ensure_ascii=False)}]
        for n in range(120):
            if 60 <= n <= 66:
                continue
            fix = {'type': 'locations', 'id': n + 1, 'session_id': 1, 'latitude': 39.9 - n * .005,
                'longitude': 116.36 + n * .0055, 'fix_utc_ms': start + n * 20000, 'fix_elapsed_ms': n * 20000 + 10000,
                'received_utc_ms': start + n * 20000, 'accuracy_m': 6, 'provider': 'gps', 'is_mock': 1}
            records.append(fix)
            for direction, offset, bps in [('phone_upload', 0, 2400000 + n * 10000), ('phone_download', 1, 4700000 - n * 15000)]:
                records.append({'type': 'samples', 'id': n * 2 + offset + 1, 'session_id': 1,
                    'seq': n + 1, 'attempt_id': 1, 'start_s': n * 20, 'end_s': n * 20 + 20, 'seconds': 20,
                    'bytes': bps * 20 // 8, 'bits_per_second': bps, 'direction': direction,
                    'sender': int(direction == 'phone_upload'), 'received_utc_ms': start + n * 20000 + 1000,
                    'label_at_receive': '城区' if n < 35 else '山区' if n < 80 else '进站', 'network_type': 'cellular',
                    'location_id': n + 1, 'location_age_ms': 1000})
        client = app.test_client()
        headers = {'Authorization': 'Bearer local-preview-upload', 'X-Railband-Device': '11111111-2222-3333-4444-555555555555'}
        result = client.post('/api/journeys/chunks', data=('\n'.join(json.dumps(row, ensure_ascii=False) for row in records) + '\n').encode(), headers=headers)
        assert result.status_code == 201
        old = (Path(__file__).parent / 'fixtures/emulator-v05.jsonl').read_bytes()
        assert client.post('/api/upload', data=old, headers=headers).status_code == 201
        app.run(host='127.0.0.1', port=8877, debug=False, use_reloader=False)


if __name__ == '__main__':
    main()
