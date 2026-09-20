"""Small, model-neutral store for the latest wearable snapshot.

The bridge uploads a normalized snapshot.  This module deliberately keeps no
vendor-specific fields in the public response so a future band/provider can
replace the Android side without changing the MCP contract.
"""
from __future__ import annotations

import json
import math
import os
import time
from datetime import datetime
from pathlib import Path
from threading import Lock
from typing import Any


FIELDS = (
    "device_name",
    "wearable_model",
    "device_id",
    "updated_at",
    "steps_today",
    "steps_measured_at",
    "heart_rate_latest",
    "heart_rate_measured_at",
    "sleep_last_night",
    "spo2_latest",
    "spo2_measured_at",
)

HISTORY_FIELDS = (
    "device_id",
    "period_start",
    "period_end",
    "timezone",
    "updated_at",
    "sleep_daily",
    "resting_heart_rate_daily",
    "steps_daily",
)


def _text(value: Any, limit: int = 120) -> str | None:
    if value is None:
        return None
    value = str(value).strip()
    return value[:limit] if value else None


def _number(value: Any, integer: bool = False) -> int | float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(result) or result < 0:
        return None
    return int(result) if integer else result


def _iso_seconds(value: Any) -> float | None:
    text = _text(value, 40)
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text[:-1] + "+00:00" if text.endswith("Z") else text)
        if parsed.tzinfo is None:
            return None
        return parsed.timestamp()
    except (TypeError, ValueError, OverflowError):
        return None


def _iso(value: Any) -> str | None:
    text = _text(value, 40)
    return text if text and _iso_seconds(text) is not None else None


def _date(value: Any) -> str | None:
    text = _text(value, 10)
    if not text:
        return None
    try:
        return datetime.strptime(text, "%Y-%m-%d").date().isoformat()
    except ValueError:
        return None


def _daily_rows(value: Any, metric: str, limit: int) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    rows = []
    for item in value[:limit]:
        if not isinstance(item, dict):
            continue
        day = _date(item.get("date"))
        if not day:
            continue
        reading = _number(item.get(metric), integer=True)
        if metric == "bpm" and reading is not None and not 1 <= reading <= 300:
            reading = None
        rows.append({"date": day, metric: reading, "measured_at": _iso(item.get("measured_at"))})
    return rows


def normalize_snapshot(payload: dict[str, Any], device_id: str, received_at: str) -> dict[str, Any]:
    """Normalize untrusted bridge input without inventing missing readings."""
    sleep = payload.get("sleep_last_night")
    if not isinstance(sleep, dict) or not sleep:
        sleep = None
    else:
        normalized_sleep = {
            "duration_minutes": _number(sleep.get("duration_minutes"), integer=True),
            "start_at": _iso(sleep.get("start_at")),
            "end_at": _iso(sleep.get("end_at")),
            "measured_at": _iso(sleep.get("measured_at")),
        }
        sleep = normalized_sleep if any(value is not None for value in normalized_sleep.values()) else None
    return {
        "device_name": _text(payload.get("device_name"), 80),
        "wearable_model": _text(payload.get("wearable_model"), 80),
        "device_id": _text(payload.get("device_id"), 80) or device_id,
        "updated_at": _iso(payload.get("updated_at")),
        "steps_today": _number(payload.get("steps_today"), integer=True),
        "steps_measured_at": _iso(payload.get("steps_measured_at")),
        "heart_rate_latest": _number(payload.get("heart_rate_latest"), integer=True),
        "heart_rate_measured_at": _iso(payload.get("heart_rate_measured_at")),
        "sleep_last_night": sleep,
        "spo2_latest": _number(payload.get("spo2_latest")),
        "spo2_measured_at": _iso(payload.get("spo2_measured_at")),
        "_received_at": received_at,
    }


def normalize_history(payload: dict[str, Any], device_id: str, received_at: str) -> dict[str, Any]:
    """Normalize the bounded daily aggregates uploaded by the Android bridge."""
    return {
        "device_id": _text(payload.get("device_id"), 80) or device_id,
        "period_start": _date(payload.get("period_start")),
        "period_end": _date(payload.get("period_end")),
        "timezone": _text(payload.get("timezone"), 80),
        "updated_at": _iso(payload.get("updated_at")),
        "sleep_daily": _daily_rows(payload.get("sleep_daily"), "duration_minutes", 7),
        "resting_heart_rate_daily": _daily_rows(payload.get("resting_heart_rate_daily"), "bpm", 7),
        "steps_daily": _daily_rows(payload.get("steps_daily"), "count", 3),
        "_received_at": received_at,
    }


def empty_snapshot(device_id: str) -> dict[str, Any]:
    return {
        "device_name": None,
        "wearable_model": None,
        "device_id": device_id,
        "updated_at": None,
        "steps_today": None,
        "steps_measured_at": None,
        "heart_rate_latest": None,
        "heart_rate_measured_at": None,
        "sleep_last_night": None,
        "spo2_latest": None,
        "spo2_measured_at": None,
    }


class WearableStateStore:
    def __init__(self, data_dir: Path) -> None:
        self.path = data_dir / "wearable_state.json"
        self.lock = Lock()
        try:
            configured_stale = int(os.environ.get("LINJIAN_WEARABLE_STALE_AFTER_SECONDS", "93600"))
        except ValueError:
            configured_stale = 93600
        self.stale_after_seconds = max(300, configured_stale)
        self.states: dict[str, dict[str, Any]] = self._load()

    def _load(self) -> dict[str, dict[str, Any]]:
        try:
            loaded = json.loads(self.path.read_text(encoding="utf-8"))
            return loaded if isinstance(loaded, dict) else {}
        except Exception:
            return {}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(self.states, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(self.path)
        try:
            self.path.chmod(0o600)
        except OSError:
            pass

    def put(self, payload: dict[str, Any], device_id: str, received_at: str) -> dict[str, Any]:
        snapshot = normalize_snapshot(payload, device_id, received_at)
        with self.lock:
            self.states[snapshot["device_id"]] = snapshot
            self._save()
        return public_snapshot(snapshot)

    def get(self, device_id: str, now: float | None = None) -> dict[str, Any]:
        with self.lock:
            snapshot = dict(self.states.get(device_id) or {})
        public = public_snapshot(snapshot, device_id)
        received = snapshot.get("_received_at")
        age = None
        if received:
            try:
                received_seconds = _iso_seconds(received)
                if received_seconds is not None:
                    age = max(0, (now if now is not None else time.time()) - received_seconds)
            except Exception:
                age = None
        stale = age is None or age > self.stale_after_seconds
        return {
            "ok": True,
            "device_id": device_id,
            "state": public,
            "freshness": {
                "stale": stale,
                "age_seconds": int(age) if age is not None else None,
                "last_sync_at": received,
                "stale_after_seconds": self.stale_after_seconds,
            },
        }


def public_snapshot(snapshot: dict[str, Any], device_id: str | None = None) -> dict[str, Any]:
    result = {field: snapshot.get(field) for field in FIELDS}
    if device_id and not result.get("device_id"):
        result["device_id"] = device_id
    return result


class WearableHistoryStore:
    def __init__(self, data_dir: Path) -> None:
        self.path = data_dir / "wearable_history.json"
        self.lock = Lock()
        try:
            configured_stale = int(os.environ.get("LINJIAN_WEARABLE_STALE_AFTER_SECONDS", "93600"))
        except ValueError:
            configured_stale = 93600
        self.stale_after_seconds = max(300, configured_stale)
        self.histories: dict[str, dict[str, Any]] = self._load()

    def _load(self) -> dict[str, dict[str, Any]]:
        try:
            loaded = json.loads(self.path.read_text(encoding="utf-8"))
            return loaded if isinstance(loaded, dict) else {}
        except Exception:
            return {}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix(".tmp")
        temp.write_text(json.dumps(self.histories, ensure_ascii=False, indent=2), encoding="utf-8")
        temp.replace(self.path)
        try:
            self.path.chmod(0o600)
        except OSError:
            pass

    def put(self, payload: dict[str, Any], device_id: str, received_at: str) -> dict[str, Any]:
        history = normalize_history(payload, device_id, received_at)
        with self.lock:
            self.histories[history["device_id"]] = history
            self._save()
        return public_history(history)

    def get(self, device_id: str, now: float | None = None) -> dict[str, Any]:
        with self.lock:
            history = dict(self.histories.get(device_id) or {})
        received = history.get("_received_at")
        received_seconds = _iso_seconds(received) if received else None
        age = max(0, (now if now is not None else time.time()) - received_seconds) if received_seconds is not None else None
        return {
            "ok": True,
            "device_id": device_id,
            "trends": public_history(history, device_id),
            "freshness": {
                "stale": age is None or age > self.stale_after_seconds,
                "age_seconds": int(age) if age is not None else None,
                "last_sync_at": received,
                "stale_after_seconds": self.stale_after_seconds,
            },
        }


def public_history(history: dict[str, Any], device_id: str | None = None) -> dict[str, Any]:
    result = {field: history.get(field) for field in HISTORY_FIELDS}
    if device_id and not result.get("device_id"):
        result["device_id"] = device_id
    result["sleep_daily"] = result.get("sleep_daily") or []
    result["resting_heart_rate_daily"] = result.get("resting_heart_rate_daily") or []
    result["steps_daily"] = result.get("steps_daily") or []
    return result
