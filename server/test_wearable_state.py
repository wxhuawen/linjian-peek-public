import json
import tempfile
import unittest
from pathlib import Path

from wearable_state import WearableStateStore, normalize_snapshot


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


if __name__ == "__main__":
    unittest.main()
