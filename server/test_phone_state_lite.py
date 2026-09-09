import calendar
import time
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

    @staticmethod
    def epoch_ms(value):
        return calendar.timegm(time.strptime(value, "%Y-%m-%dT%H:%M:%SZ")) * 1000

    def ime_snapshot(self, at):
        updated_at_ms = self.epoch_ms(at)
        state = {
            "updated_at_local": "2026-09-09 22:18:12",
            "updated_at_ms": updated_at_ms,
            "current_app": "搜狗输入法",
            "current_package": "com.sohu.inputmethod.sogou",
            "screen_on": True,
        }
        lite = dict(state)
        lite["screen_text_lite"] = "键盘候选词，不应返回"
        return state, lite

    def test_chatgpt_keyboard_uses_host_event_before_snapshot(self):
        full, lite = self.ime_snapshot("2026-09-09T14:18:12Z")
        events = [
            {
                "device_id": "android-phone", "source": "phone", "type": "app_open",
                "action": "foreground_changed", "created_at": "2026-09-09T14:19:10Z",
                "app_name": "微信", "package_name": "com.tencent.mm",
            },
            {
                "device_id": "android-phone", "source": "phone", "type": "app_open",
                "action": "foreground_changed", "created_at": "2026-09-09T14:17:52Z",
                "app_name": "ChatGPT", "package_name": "com.openai.chatgpt",
            },
        ]
        actual = current_phone_state_lite(full, lite, events, "android-phone")
        self.assertEqual(actual["current_app"], "ChatGPT")
        self.assertEqual(actual["current_package"], "com.openai.chatgpt")
        self.assertEqual(actual["screen_text_lite"], "")

    def test_wechat_keyboard_uses_latest_host_event(self):
        full, lite = self.ime_snapshot("2026-09-09T14:21:20Z")
        events = [
            {
                "device_id": "android-phone", "source": "phone", "type": "app_open",
                "action": "foreground_changed", "created_at": "2026-09-09T14:21:00Z",
                "app_name": "微信", "package_name": "com.tencent.mm",
            },
            {
                "device_id": "android-phone", "source": "phone", "type": "app_open",
                "action": "foreground_changed", "created_at": "2026-09-09T14:17:52Z",
                "app_name": "ChatGPT", "package_name": "com.openai.chatgpt",
            },
        ]
        actual = current_phone_state_lite(full, lite, events, "android-phone")
        self.assertEqual(actual["current_app"], "微信")
        self.assertEqual(actual["current_package"], "com.tencent.mm")
        self.assertEqual(actual["screen_text_lite"], "")

    def test_client_corrected_host_snapshot_keeps_host_text(self):
        full, _ = self.ime_snapshot("2026-09-09T14:18:12Z")
        lite = {
            "updated_at_local": full["updated_at_local"],
            "updated_at_ms": full["updated_at_ms"],
            "current_app": "ChatGPT",
            "current_package": "com.openai.chatgpt",
            "screen_on": True,
            "screen_text_lite": "Message ChatGPT | Send",
        }
        actual = current_phone_state_lite(full, lite)
        self.assertEqual(actual["current_app"], "ChatGPT")
        self.assertEqual(actual["current_package"], "com.openai.chatgpt")
        self.assertEqual(actual["screen_text_lite"], "Message ChatGPT | Send")

    def test_ime_without_host_history_never_returns_ime(self):
        full, lite = self.ime_snapshot("2026-09-09T14:18:12Z")
        actual = current_phone_state_lite(full, lite, [], "android-phone")
        self.assertEqual(actual["current_app"], "")
        self.assertEqual(actual["current_package"], "")
        self.assertEqual(actual["screen_text_lite"], "")


if __name__ == "__main__":
    unittest.main()
