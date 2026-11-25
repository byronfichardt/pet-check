"""Decides when enough noise has built up to alert the owner.

Mirrors app/src/main/java/.../monitor/AlertEvaluator.kt.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass

from .classify import SOUND_TYPES


@dataclass(frozen=True)
class AlertRule:
    enabled: bool = False
    noisy_seconds: int = 60       # alert once this much noise...
    window_minutes: int = 10      # ...has been heard within this many minutes
    cooldown_minutes: int = 30    # stay quiet this long after an alert
    include_other: bool = False   # count sounds that were not recognised as a pet


@dataclass(frozen=True)
class AlertTrigger:
    at_ms: int
    noisy_seconds: int
    window_minutes: int
    type: str
    event_count: int


class AlertEvaluator:
    def __init__(self):
        self._recent: deque[tuple[int, int, str]] = deque()
        self._last_alert_ms: int | None = None

    def on_event(self, rule: AlertRule, start_ms: int, duration_ms: int, kind: str) -> AlertTrigger | None:
        """Call for each finished event, in order. Returns a trigger when the rule is met."""
        if not rule.enabled:
            self._recent.clear()
            return None
        if kind == "other" and not rule.include_other:
            return None

        end_ms = start_ms + duration_ms
        self._recent.append((start_ms, end_ms, kind))
        window_start = end_ms - rule.window_minutes * 60_000
        while self._recent and self._recent[0][1] <= window_start:
            self._recent.popleft()

        if self._last_alert_ms is not None and end_ms - self._last_alert_ms < rule.cooldown_minutes * 60_000:
            return None

        ms_by_type = dict.fromkeys(SOUND_TYPES, 0)
        total = 0
        for entry_start, entry_end, entry_kind in self._recent:
            overlap = min(entry_end, end_ms) - max(entry_start, window_start)
            if overlap > 0:
                ms_by_type[entry_kind] += overlap
                total += overlap
        if total < rule.noisy_seconds * 1000:
            return None

        self._last_alert_ms = end_ms
        dominant = max(SOUND_TYPES, key=lambda kind: ms_by_type[kind])
        return AlertTrigger(end_ms, total // 1000, rule.window_minutes, dominant, len(self._recent))
