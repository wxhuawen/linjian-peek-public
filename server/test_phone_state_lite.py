import unittest

from server.linjian_server import current_phone_state_lite


class PhoneStateLiteTest(unittest.TestCase):
    def setUp(self):
        self.full = {
            "updated_at_local": "2026-09-04 14:00:00",
            "updated_at_ms": 1788501600000,
            "current_app": "小红书",
            "current_package": "com.xingin.xhs",
            "screen_on": True,
            "weather_state": {"city": "杭州"},
        }

    def test_matching_snapshot_keeps_lite_text(self):
        lite = {key: self.full[key] for key in (
            "updated_at_local", "updated_at_ms", "current_app", "current_package", "screen_on"
        )}
        lite["screen_text_lite"] = "首页 | 推荐"
        actual = current_phone_state_lite(self.full, lite)
        self.assertEqual(actual["screen_text_lite"], "首页 | 推荐")
        self.assertEqual(set(actual), {
            "ok", "updated_at_local", "updated_at_ms", "current_app",
            "current_package", "screen_on", "screen_text_lite",
        })

    def test_stale_snapshot_uses_current_scalars_and_drops_text(self):
        stale = {
            "updated_at_local": "2026-09-04 13:59:00",
            "updated_at_ms": 1788501540000,
            "current_app": "微信",
            "current_package": "com.tencent.mm",
            "screen_on": True,
            "screen_text_lite": "旧页面文字",
        }
        actual = current_phone_state_lite(self.full, stale)
        self.assertEqual(actual["updated_at_ms"], self.full["updated_at_ms"])
        self.assertEqual(actual["current_app"], self.full["current_app"])
        self.assertEqual(actual["current_package"], self.full["current_package"])
        self.assertEqual(actual["screen_text_lite"], "")


if __name__ == "__main__":
    unittest.main()
