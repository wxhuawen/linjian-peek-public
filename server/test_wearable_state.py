import json
import os
import tempfile
import threading
import unittest
from datetime import datetime
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch
from urllib.request import Request, urlopen

from linjian_server import Handler, State
from wearable_state import WearableHistoryStore, WearableStateStore, normalize_history, normalize_snapshot


class WearableStateTests(unittest.TestCase):
    def test_missing_values_stay_null(self):
        snapshot = normalize_snapshot({"device_name": "Band"}, "android-phone", "2026-01-01T00:00:00Z")
        self.assertIsNone(snapshot["steps_today"])
        self.assertIsNone(snapshot["sleep_last_night"])

    def test_invalid_values_are_not_persisted_as_readings(self):
        snapshot = normalize_snapshot({
            "steps_today": float("nan"),
            "heart_rate_latest": -1,
            "spo2_latest": float("inf"),
            "sleep_last_night": {"duration_minutes": -1, "start_at": "not-a-time"},
            "updated_at": "not-a-time",
        }, "android-phone", "2026-01-01T00:00:00Z")
        self.assertIsNone(snapshot["steps_today"])
        self.assertIsNone(snapshot["heart_rate_latest"])
        self.assertIsNone(snapshot["spo2_latest"])
        self.assertIsNone(snapshot["sleep_last_night"])
        self.assertIsNone(snapshot["updated_at"])

    def test_android_fractional_instants_are_preserved(self):
        snapshot = normalize_snapshot({
            "updated_at": "2026-08-27T07:00:00.123Z",
            "heart_rate_measured_at": "2026-08-27T15:00:00.123+08:00",
        }, "android-phone", "2026-08-27T07:00:01Z")
        self.assertEqual(snapshot["updated_at"], "2026-08-27T07:00:00.123Z")
        self.assertEqual(snapshot["heart_rate_measured_at"], "2026-08-27T15:00:00.123+08:00")

    def test_store_survives_reload_and_marks_fresh(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            store = WearableStateStore(path)
            store.put({"device_name": "Band", "steps_today": 12}, "android-phone", "2026-01-01T00:00:00Z")
            self.assertEqual(json.loads((path / "wearable_state.json").read_text())["android-phone"]["steps_today"], 12)
            reloaded = WearableStateStore(path)
            result = reloaded.get("android-phone", now=1767225601)
            self.assertEqual(result["state"]["steps_today"], 12)
            self.assertFalse(result["freshness"]["stale"])

    def test_history_keeps_null_days_and_rejects_invalid_resting_rate(self):
        history = normalize_history({
            "period_start": "2026-09-14",
            "period_end": "2026-09-20",
            "timezone": "Asia/Shanghai",
            "sleep_daily": [
                {"date": "2026-09-19", "duration_minutes": 407, "measured_at": "2026-09-19T23:10:00Z"},
                {"date": "2026-09-20", "duration_minutes": None, "measured_at": None},
            ],
            "resting_heart_rate_daily": [
                {"date": "2026-09-20", "bpm": 999, "measured_at": "2026-09-20T01:00:00Z"},
            ],
            "steps_daily": [{"date": "2026-09-20", "count": 1087, "measured_at": "2026-09-20T01:13:00Z"}],
        }, "android-phone", "2026-09-20T01:15:00Z")
        self.assertEqual(history["sleep_daily"][0]["duration_minutes"], 407)
        self.assertIsNone(history["sleep_daily"][1]["duration_minutes"])
        self.assertIsNone(history["resting_heart_rate_daily"][0]["bpm"])
        self.assertEqual(history["steps_daily"][0]["count"], 1087)

    def test_history_store_survives_reload_and_has_independent_freshness(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            store = WearableHistoryStore(path)
            store.put({
                "period_start": "2026-09-14", "period_end": "2026-09-20", "timezone": "Asia/Shanghai",
                "sleep_daily": [], "resting_heart_rate_daily": [],
                "steps_daily": [{"date": "2026-09-20", "count": 1087, "measured_at": "2026-09-20T01:13:00Z"}],
            }, "android-phone", "2026-09-20T01:15:00Z")
            reloaded = WearableHistoryStore(path)
            now = datetime.fromisoformat("2026-09-20T01:16:00+00:00").timestamp()
            result = reloaded.get("android-phone", now=now)
            self.assertEqual(result["trends"]["steps_daily"][0]["count"], 1087)
            self.assertEqual(result["freshness"]["last_sync_at"], "2026-09-20T01:15:00Z")
            self.assertEqual(result["freshness"]["age_seconds"], 60)
            self.assertFalse(result["freshness"]["stale"])

    def test_history_http_round_trip(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
            "LINJIAN_DATA_DIR": directory,
            "LINJIAN_TOKEN": "test-token",
        }):
            state = State()
            Handler.state = state
            httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            thread = threading.Thread(target=httpd.serve_forever, daemon=True)
            thread.start()
            try:
                url = f"http://127.0.0.1:{httpd.server_port}/api/wearable/history"
                payload = json.dumps({
                    "device_id": "android-phone", "period_start": "2026-09-14", "period_end": "2026-09-20",
                    "timezone": "Asia/Shanghai", "sleep_daily": [], "resting_heart_rate_daily": [],
                    "steps_daily": [{"date": "2026-09-20", "count": 1087, "measured_at": "2026-09-20T01:13:00Z"}],
                }).encode()
                request = Request(url, data=payload, method="POST", headers={
                    "Content-Type": "application/json", "X-Auth-Token": "test-token",
                })
                with urlopen(request) as response:
                    self.assertTrue(json.load(response)["ok"])
                with urlopen(Request(url, headers={"X-Auth-Token": "test-token"})) as response:
                    result = json.load(response)
                self.assertEqual(result["trends"]["steps_daily"][0]["count"], 1087)
            finally:
                httpd.shutdown()
                httpd.server_close()
                thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
