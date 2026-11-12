import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from app import create_app, hash_secret, parse_upload

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURE = (ROOT / "server/fixtures/emulator-v05.jsonl").read_bytes()
DEVICE = "11111111-2222-3333-4444-555555555555"


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=".test-", dir=ROOT / "server")
        self.app = create_app({
            "TESTING": True, "SECRET_KEY": "test-only-secret",
            "ADMIN_HASH": hash_secret("admin-test-password"),
            "UPLOAD_HASH": hash_secret("upload-test-token"),
            "DATABASE": str(pathlib.Path(self.temp.name) / "archive.sqlite3"),
        })
        self.client = self.app.test_client()

    def tearDown(self):
        self.temp.cleanup()

    def upload(self, data=FIXTURE, token="upload-test-token", device=DEVICE):
        return self.client.post("/api/upload", data=data, headers={
            "Authorization": "Bearer " + token, "X-Railband-Device": device,
            "Content-Type": "application/x-ndjson"})

    def login(self):
        response = self.client.post("/login", data={"password": "admin-test-password"})
        self.assertEqual(response.status_code, 302)
        with self.client.session_transaction() as session:
            return session["csrf"]

    def test_full_flow(self):
        self.assertEqual(self.client.get("/").status_code, 302)
        self.assertEqual(self.client.get("/download/1").status_code, 302)
        self.assertEqual(self.upload(token="wrong").status_code, 401)
        saved = self.upload()
        self.assertEqual(saved.status_code, 201)
        item_id = saved.json["id"]
        self.assertEqual(self.upload().status_code, 200)
        self.assertEqual(self.upload(device="22222222-2222-3333-4444-555555555555").status_code, 201)
        csrf = self.login()
        listing = self.client.get("/")
        self.assertEqual(listing.status_code, 200)
        self.assertIn(b"unknown Android SDK", listing.data)
        downloaded = self.client.get(f"/download/{item_id}")
        self.assertEqual(downloaded.data, FIXTURE)
        self.assertIn("attachment", downloaded.headers["Content-Disposition"])
        self.assertEqual(self.client.get(f"/delete/{item_id}").status_code, 200)
        self.assertEqual(self.client.post(f"/delete/{item_id}", data={"confirm": "delete"}).status_code, 403)
        self.assertEqual(self.client.post(f"/delete/{item_id}", data={"confirm": "delete", "csrf": csrf}).status_code, 302)
        self.assertEqual(self.client.get(f"/download/{item_id}").status_code, 404)

    def test_rejects_mixed_and_incomplete_exports(self):
        lines = FIXTURE.decode().splitlines()
        self.assertGreater(parse_upload(FIXTURE)["sample_count"], 0)
        self.assertEqual(self.upload(data=("\n".join(lines[:2]) + "\n").encode()).status_code, 201)
        changed = [json.loads(line) for line in lines]
        changed[2]["session_id"] = 999
        self.assertEqual(self.upload(data=("\n".join(json.dumps(row) for row in changed) + "\n").encode()).status_code, 400)
        changed[2]["session_id"] = 1
        changed[1]["status"] = "running"
        self.assertEqual(self.upload(data=("\n".join(json.dumps(row) for row in changed) + "\n").encode()).status_code, 400)


if __name__ == "__main__":
    unittest.main()
