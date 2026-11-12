import hashlib
import json
import unittest
from test_app import ArchiveTest, DEVICE
from journeys import MAX_CHUNK, parse_chunk

TRIP = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'


def chunk(index=0, final=False):
    config = {'journey_id': TRIP, 'device': 'synthetic journey test', 'route': 'test only'}
    rows = [
        {'type': 'metadata', 'schema_version': 5, 'format': 'railband_journey_chunk', 'journey_id': TRIP, 'chunk_index': index, 'is_final': final},
        {'type': 'sessions', 'id': 100, 'journey_id': TRIP, 'started_utc_ms': 1000, 'ended_utc_ms': 2000 if final else None, 'status': 'cancelled' if final else 'running', 'config': json.dumps(config)},
        {'type': 'samples', 'id': index+1, 'session_id': 100, 'direction': 'phone_upload', 'attempt_id': index+1},
    ]
    return ('\n'.join(json.dumps(row) for row in rows)+'\n').encode()


class JourneyTest(ArchiveTest):
    def send(self, body, token='upload-test-token'):
        return self.client.post('/api/journeys/chunks', data=body, headers={'Authorization': 'Bearer '+token, 'X-Railband-Device': DEVICE})

    def test_out_of_order_retry_and_completion(self):
        last = chunk(1, True)
        saved = self.send(last)
        self.assertEqual(saved.status_code, 201)
        trip = saved.json['id']
        self.assertEqual(saved.json['sha256'], hashlib.sha256(last).hexdigest())
        self.assertEqual(self.send(last).status_code, 200)
        self.login()
        partial = self.client.get(f'/journeys/{trip}/download')
        self.assertFalse(json.loads(partial.data.splitlines()[0])['complete'])
        self.assertEqual(self.send(chunk()).status_code, 201)
        response = self.client.get(f'/journeys/{trip}/download')
        header, data = response.data.split(b'\n', 1)
        self.assertTrue(json.loads(header)['complete'])
        self.assertEqual(data, chunk()+last)
        self.assertEqual(self.send(chunk(2)).status_code, 409)
        self.assertEqual(self.send(chunk(0, True)).status_code, 409)
        self.assertIn('已完整接收'.encode(), self.client.get('/journeys').data)

    def test_auth_validation_and_tombstone(self):
        self.assertEqual(self.send(chunk(), 'wrong').status_code, 401)
        self.assertEqual(self.send(b'x'*(MAX_CHUNK+1)).status_code, 413)
        bad = chunk().replace(b'"session_id": 100', b'"session_id": 999')
        self.assertEqual(self.send(bad).status_code, 400)
        saved = self.send(chunk())
        trip = saved.json['id']
        self.assertEqual(self.client.get(f'/journeys/{trip}/download').status_code, 302)
        csrf = self.login()
        self.assertEqual(self.client.post(f'/journeys/{trip}/delete', data={'confirm':'delete'}).status_code, 403)
        self.assertEqual(self.client.post(f'/journeys/{trip}/delete', data={'confirm':'delete','csrf':csrf}).status_code, 302)
        self.assertEqual(self.send(chunk(1, True)).status_code, 410)
        self.assertEqual(self.send(chunk()).status_code, 410)
        self.assertEqual(self.client.get(f'/journeys/{trip}/download').status_code, 404)


if __name__ == '__main__':
    unittest.main()
