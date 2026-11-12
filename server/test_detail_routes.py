"""HTTP-level regression checks for authenticated measurement maps."""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from app import create_app, hash_secret

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEVICE = "11111111-2222-3333-4444-555555555555"
TRIP = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
START = 1790000000000
ROUTE = '<img src=x onerror="window.mapXss=1">山路 & 隧道'
ADMIN_HASH = hash_secret("map-test-admin")
UPLOAD_HASH = hash_secret("map-test-upload")


def jsonl(rows):
    return ("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n").encode("utf-8")


def location(key, seconds, lat=39.0, lon=116.0):
    return {"type": "locations", "id": key, "session_id": 100,
            "fix_utc_ms": START + seconds * 1000,
            "fix_elapsed_ms": 10000 + seconds * 1000,
            "received_utc_ms": START + seconds * 1000 + 10,
            "latitude": lat, "longitude": lon, "accuracy_m": 5, "provider": "gps"}


def sample(key, seconds, rate, duration, direction="phone_upload"):
    return {"type": "samples", "id": key, "session_id": 100, "seq": key,
            "received_utc_ms": START + seconds * 1000,
            "seconds": duration, "bytes": rate * duration / 8,
            "bits_per_second": rate, "direction": direction,
            "sender": int(direction == "phone_upload"),
            "journey_start_s": seconds - duration, "journey_end_s": seconds,
            "start_s": seconds - duration, "end_s": seconds,
            "label_at_receive": ROUTE}


def journey_block(index, records, final=False):
    config = {"journey_id": TRIP, "route": ROUTE, "device": "map test device",
              "duration_s": 0, "sample_interval_s": 1, "direction": "bidirectional"}
    return jsonl([
        {"type": "metadata", "schema_version": 5, "format": "railband_journey_chunk",
         "journey_id": TRIP, "chunk_index": index, "is_final": final},
        {"type": "sessions", "id": 100, "journey_id": TRIP,
         "started_utc_ms": START, "ended_utc_ms": START + 180000 if final else None,
         "status": "completed" if final else "running", "config": json.dumps(config)},
        *records,
    ])


class DetailRoutesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=".map-test-", dir=ROOT / "server")
        self.app = create_app({"TESTING": True, "SECRET_KEY": "map-test-only",
                               "ADMIN_HASH": ADMIN_HASH, "UPLOAD_HASH": UPLOAD_HASH,
                               "DATABASE": str(pathlib.Path(self.temp.name) / "archive.sqlite3")})
        self.client = self.app.test_client()

    def tearDown(self):
        self.temp.cleanup()

    def login(self):
        self.assertEqual(self.client.post("/login", data={"password": "map-test-admin"}).status_code, 302)
        with self.client.session_transaction() as session:
            return session["csrf"]

    def send(self, endpoint, body):
        return self.client.post(endpoint, data=body, headers={
            "Authorization": "Bearer map-test-upload", "X-Railband-Device": DEVICE,
            "Content-Type": "application/x-ndjson"})

    def ordinary(self):
        rows = [json.loads(line) for line in (ROOT / "server/fixtures/emulator-v05.jsonl").read_text(encoding="utf-8").splitlines()]
        config = json.loads(rows[1]["config"])
        config["route"] = ROUTE
        rows[1]["config"] = json.dumps(config)
        saved = self.send("/api/upload", jsonl(rows))
        self.assertEqual(saved.status_code, 201)
        return saved.json["id"]

    def test_auth_missing_and_escaped_html(self):
        ordinary_id = self.ordinary()
        saved = self.send("/api/journeys/chunks", journey_block(0, [], final=True))
        self.assertEqual(saved.status_code, 201)
        journey_id = saved.json["id"]
        for base in (f"/measurements/{ordinary_id}", f"/journeys/{journey_id}"):
            for path in (base, base + "/data"):
                response = self.client.get(path)
                self.assertEqual(response.status_code, 302)
                self.assertTrue(response.location.endswith("/login"))
        self.login()
        for base in (f"/measurements/{ordinary_id}", f"/journeys/{journey_id}"):
            page = self.client.get(base)
            self.assertEqual(page.status_code, 200)
            self.assertIn(b"data-url=", page.data)
            self.assertNotIn(ROUTE.encode(), page.data)
            self.assertIn(b"&lt;img", page.data)
            data = self.client.get(base + "/data")
            self.assertEqual(data.status_code, 200)
            self.assertEqual(data.json["item"]["route"], ROUTE)
            self.assertIn("application/json", data.content_type)
            self.assertEqual(data.headers["Cache-Control"], "no-store")
            self.assertEqual(data.headers["X-Content-Type-Options"], "nosniff")
        for path in ("/measurements/999999", "/measurements/999999/data", "/journeys/999999", "/journeys/999999/data"):
            self.assertEqual(self.client.get(path).status_code, 404)

    def test_map_policy_only_applies_to_map_html(self):
        item_id = self.ordinary()
        self.login()
        response = self.client.get(f"/measurements/{item_id}")
        policy = response.headers["Content-Security-Policy"]
        self.assertIn("script-src 'self'", policy)
        self.assertIn("https://tile.openstreetmap.org", policy)
        self.assertIn("style-src-attr 'unsafe-inline'", policy)
        self.assertEqual(response.headers["Referrer-Policy"], "strict-origin-when-cross-origin")
        self.assertEqual(self.client.get("/").headers["Referrer-Policy"], "no-referrer")
        self.assertEqual(self.client.get("/login").headers["Referrer-Policy"], "no-referrer")

    def test_deleted_records_no_longer_have_detail_data(self):
        ordinary_id = self.ordinary()
        saved = self.send("/api/journeys/chunks", journey_block(0, [], final=True))
        journey_id = saved.json["id"]
        csrf = self.login()
        form = {"confirm": "delete", "csrf": csrf}
        self.assertEqual(self.client.post(f"/delete/{ordinary_id}", data=form).status_code, 302)
        self.assertEqual(self.client.post(f"/journeys/{journey_id}/delete", data=form).status_code, 302)
        for base in (f"/measurements/{ordinary_id}", f"/journeys/{journey_id}"):
            self.assertEqual(self.client.get(base).status_code, 404)
            self.assertEqual(self.client.get(base + "/data").status_code, 404)

    def test_partial_chunk_track_and_weighted_rates(self):
        first_location = location(1, 0)
        first_sample = sample(1, 1, 1000000, 1)
        carried = {"type": "telemetry", "id": 1, "session_id": 100, "raw": json.dumps({
            "utc_ms": START + 119000, "elapsed_ms": 129000,
            "latitude": first_location["latitude"], "longitude": first_location["longitude"],
            "location": first_location, "location_state": "carried", "location_age_ms": 119000,
            "network": {"network_type": "cellular"}, "label": ROUTE,
        })}
        # The final block may arrive first. Rows repeated across immutable blocks
        # must not be counted twice, and a carried fix must not invent movement.
        final = self.send("/api/journeys/chunks", journey_block(2, [
            first_location, location(3, 120, 39.02, 116.02), location(4, 150, 39.03, 116.03),
            first_sample, sample(2, 34, 3000000, 3), sample(3, 34, 4000000, 1, "phone_download"), carried,
        ], final=True))
        self.assertEqual(final.status_code, 201)
        trip_id = final.json["id"]
        self.assertEqual(self.send("/api/journeys/chunks", journey_block(0, [
            first_location, location(2, 30, 39.01, 116.01), first_sample,
        ])).status_code, 201)
        self.login()
        response = self.client.get(f"/journeys/{trip_id}/data")
        self.assertEqual(response.status_code, 200)
        detail = response.json
        self.assertFalse(detail["item"]["complete"])
        self.assertEqual(detail["item"]["received_chunks"], 2)
        self.assertEqual(detail["item"]["expected_chunks"], 3)
        self.assertEqual(detail["summary"]["status"], "completed")
        self.assertEqual(detail["summary"]["sample_count"], 3)
        self.assertAlmostEqual(detail["summary"]["upload"]["average_bps"], 2500000)
        self.assertAlmostEqual(detail["summary"]["download"]["average_bps"], 4000000)
        self.assertEqual(detail["summary"]["upload"]["seconds"], 4)
        self.assertEqual(detail["track"]["coordinate_system"], "WGS84")
        self.assertEqual(detail["track"]["fix_count"], 4)
        self.assertEqual(len(detail["track"]["segments"]), 2)
        points = [point for segment in detail["track"]["segments"] for point in segment]
        self.assertEqual([point["fix_utc_ms"] for point in points], [START + seconds * 1000 for seconds in (0, 30, 120, 150)])
        self.assertEqual(self.send("/api/journeys/chunks", journey_block(1, [])).status_code, 201)
        filled = self.client.get(f"/journeys/{trip_id}/data").json
        self.assertTrue(filled["item"]["complete"])
        self.assertEqual(filled["summary"]["status"], "completed")
        self.assertEqual(len(filled["track"]["segments"]), 1)


if __name__ == "__main__":
    unittest.main()
