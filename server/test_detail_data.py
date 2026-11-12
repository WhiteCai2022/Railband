import json
import math
from pathlib import Path
import unittest

from detail_data import build_detail


def payload(rows):
    return ("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n").encode()


def session(status="completed", ended=14000):
    return {"type": "sessions", "id": 1, "started_utc_ms": 1000,
            "started_elapsed_ms": 500, "ended_utc_ms": ended, "status": status,
            "config": json.dumps({"route": "山间铁路", "device": "phone", "direction": "bidirectional"})}


def fix(identifier, second, latitude=30, longitude=120, **extra):
    return dict(type="locations", session_id=1, id=identifier,
                fix_utc_ms=1000 + second * 1000, fix_elapsed_ms=500 + second * 1000,
                latitude=latitude, longitude=longitude, accuracy_m=5, **extra)


def sample(identifier, seq, direction="phone_upload", seconds=1, count=125000, **extra):
    return dict(type="samples", id=identifier, session_id=1, seq=seq, attempt_id=1,
                direction=direction, seconds=seconds, bytes=count,
                bits_per_second=count * 8 / seconds, start_s=seq - 1, end_s=seq - 1 + seconds,
                received_utc_ms=1000 + seq * 1000, **extra)


def chunk(index, rows, final=False):
    return payload([{"type": "metadata", "schema_version": 5,
                     "format": "railband_journey_chunk", "chunk_index": index, "is_final": final}] + rows)


class DetailDataTest(unittest.TestCase):
    def test_weighted_directions_duplicate_rows_latest_snapshot(self):
        first = sample(1, 1, seconds=1, count=125000)
        down = sample(2, 1, "phone_download", seconds=1, count=250000)
        second = sample(3, 2, seconds=3, count=1500000)
        down_second = sample(4, 2, "phone_download", seconds=3, count=750000)
        result = build_detail(iter([chunk(0, [session("running", None), first, down]),
                                  chunk(1, [session(), first, second, down_second])]))
        summary = result["summary"]
        self.assertEqual(summary["status"], "completed")
        self.assertEqual(summary["duration_s"], 13)
        self.assertEqual(summary["sample_count"], 4)
        self.assertEqual(summary["interval_count"], 2)
        self.assertEqual(summary["upload"]["seconds"], 4)
        self.assertEqual(summary["upload"]["average_bps"], 3250000)
        self.assertEqual(summary["download"]["average_bps"], 2000000)
        self.assertIsNone(result["track"]["bounds"])
        self.assertTrue(result["warnings"])

    def test_locations_carries_joined_samples_nested_telemetry(self):
        # A joined sample references the fix; old carries must not extend track.
        joined = sample(1, 1, location_id=10, latitude=30, longitude=120,
                        location_fix_utc_ms=1000, location_fix_elapsed_ms=500,
                        location_accuracy_m=5, location_age_ms=1000,
                        network_type="cellular", label_at_receive="山区")
        down = dict(joined, id=2, direction="phone_download", bytes=250000, bits_per_second=2000000)
        raw = {"utc_ms": 200000, "location": fix(10, 0),
               "location_age_ms": 199000, "location_state": "carried",
               "network": {"network_type": "none"}, "label": "隧道"}
        rows = [session(), fix(10, 0), joined, down,
                {"type": "telemetry", "id": 1, "session_id": 1, "raw": json.dumps(raw)},
                # Separate new actual fix from a previously carried one.
                fix(11, 2, longitude=120.001)]
        result = build_detail([payload(rows)])
        track = result["track"]
        self.assertEqual(track["fix_count"], 2)
        self.assertEqual(track["display_point_count"], 2)
        point = track["segments"][0][0]
        self.assertEqual(point["label"], "山区")
        self.assertEqual(point["up_bps"], 1000000)
        self.assertEqual(point["down_bps"], 2000000)
        self.assertEqual(point["location_age_ms"], 1000)
        self.assertEqual(result["summary"]["telemetry_count"], 1)
        self.assertEqual(result["summary"]["networks"], ["cellular", "none"])
        self.assertEqual(track["coordinate_system"], "WGS84")

    def test_old_location_id_association_and_legacy_sender(self):
        older = sample(1, 1, location_id=10, location_age_ms=500)
        del older["direction"]
        older["sender"] = 1
        result = build_detail([payload([session(), older, fix(10, 0)])])
        point = result["track"]["segments"][0][0]
        self.assertEqual(point["up_bps"], 1000000)
        self.assertEqual(point["location_age_ms"], 500)
        self.assertEqual(result["summary"]["upload"]["average_bps"], 1000000)

    def test_gap_jump_and_lone_endpoint_are_separate(self):
        result = build_detail([chunk(0, [session(), fix(1, 0), fix(2, 1, longitude=120.001)]),
                               chunk(2, [session(), fix(3, 2, longitude=120.002),
                                         fix(4, 123, longitude=120.003),
                                         fix(5, 124, latitude=40, longitude=116)])], complete=False)
        track = result["track"]
        self.assertEqual([len(segment) for segment in track["segments"]], [2, 1, 1, 1])
        self.assertEqual(track["gap_count"], 3)
        self.assertEqual(track["fix_count"], 5)
        self.assertFalse(result["summary"]["complete"])
        self.assertTrue(any("数据块" in warning for warning in result["warnings"]))

    def test_missing_invalid_nonfinite_bool_and_mock_coordinates(self):
        rows = [session(), fix(1, 0, latitude=None, longitude=None),
                fix(2, 1, latitude=True), fix(3, 2, latitude=math.nan),
                fix(4, 3, longitude=math.inf), fix(5, 4, latitude=91),
                fix(6, 5, latitude=0, longitude=0, is_mock=1),
                {"type": "samples", "id": 1, "session_id": 1, "seconds": False,
                 "bytes": True, "bits_per_second": math.inf, "direction": "phone_upload"},
                {"type": "telemetry", "id": 1, "session_id": 1, "raw": "[]"}]
        result = build_detail([payload(rows) + b"not JSON\n[]\n"])
        self.assertEqual(result["track"]["fix_count"], 1)
        self.assertEqual(result["track"]["bounds"], [0, 0, 0, 0])
        self.assertEqual(result["track"]["invalid_coordinates"], 4)
        self.assertEqual(result["track"]["malformed_rows"], 2)
        self.assertEqual(result["track"]["mock_fix_count"], 1)
        self.assertIsNone(result["summary"]["upload"]["average_bps"])
        json.dumps(result, allow_nan=False)

    def test_long_route_display_is_bounded_preserves_endpoints_labels(self):
        rows = [session()]
        for index in range(12000):
            rows.append(fix(index + 1, index, longitude=120 + index * .00001))
        rows.extend([{"type": "labels", "id": 1, "session_id": 1, "utc_ms": 1000, "label": "城区"},
                     {"type": "labels", "id": 2, "session_id": 1, "utc_ms": 6001000, "label": "山区"}])
        result = build_detail([payload(rows)], max_points=100)
        track = result["track"]
        points = [point for segment in track["segments"] for point in segment]
        self.assertEqual(track["fix_count"], 12000)
        self.assertLessEqual(len(points), 100)
        self.assertTrue(track["reduced"])
        self.assertEqual(points[0]["lon"], 120)
        self.assertEqual(points[-1]["lon"], 120.11999)
        self.assertEqual({point["label"] for point in points}, {"城区", "山区"})
        self.assertTrue(track["preserved_all_boundaries_and_labels"])

    def test_arbitrary_byte_boundaries_unicode(self):
        encoded = payload([session(), fix(1, 0)])
        result = build_detail(encoded[i:i + 7] for i in range(0, len(encoded), 7))
        self.assertEqual(result["summary"]["route"], "山间铁路")
        self.assertEqual(result["track"]["fix_count"], 1)

    def test_more_boundaries_than_budget_preserves_global_endpoints(self):
        # Every actual fix has a different label and is after a GPS gap. There
        # is no possible way to retain all endpoints within this tiny budget.
        rows = [session()]
        for index in range(14):
            rows.append(fix(index + 1, index * 121, longitude=120 + index * .00001))
            rows.append({"type": "labels", "id": index + 1, "session_id": 1,
                         "utc_ms": 1000 + index * 121000, "label": "scene " + str(index)})
        result = build_detail([payload(rows)], max_points=4)
        track = result["track"]
        self.assertEqual([len(segment) for segment in track["segments"]], [1, 1, 1, 1])
        self.assertEqual(track["segments"][0][0]["lon"], 120)
        self.assertEqual(track["segments"][-1][-1]["lon"], 120.00013)
        self.assertFalse(track["preserved_all_boundaries_and_labels"])
        self.assertEqual(track["display_point_count"], 4)

    def test_actual_exports(self):
        workspace = Path(__file__).resolve().parents[2]
        old = workspace / "test" / "railband-1790068581992.jsonl"
        directory = workspace / "output" / "server" / "reconnect-chunk-regression"
        if not old.exists() or not directory.exists():
            self.skipTest("Local real export regression inputs are not shipped on server")
        rows = [json.loads(line) for line in old.read_text(encoding="utf-8").splitlines()]
        one = [row for row in rows if row.get("type") == "metadata" or row.get("type") == "sessions" and row.get("id") == 3 or row.get("session_id") == 3]
        result = build_detail([payload(one)])
        actual = [row for row in one if row.get("type") == "samples"]
        expected = sum(row["bytes"] for row in actual) * 8 / sum(row["seconds"] for row in actual)
        self.assertAlmostEqual(result["summary"]["upload"]["average_bps"], expected)
        self.assertEqual(result["track"]["fix_count"], 0)
        blocks = [(directory / f"chunk-{index}.jsonl").read_bytes() for index in (0, 1)]
        journey = build_detail(iter(blocks))
        local = build_detail([(directory / "local-export.jsonl").read_bytes()])
        self.assertNotEqual(journey["summary"]["status"], "running")
        self.assertEqual(journey["summary"]["sample_count"], local["summary"]["sample_count"])
        self.assertEqual(journey["summary"]["upload"], local["summary"]["upload"])
        self.assertEqual(journey["track"]["fix_count"], local["track"]["fix_count"])
        self.assertGreater(journey["summary"]["telemetry_count"], 0)
        self.assertGreater(journey["track"]["fix_count"], 0)


if __name__ == "__main__":
    unittest.main()
