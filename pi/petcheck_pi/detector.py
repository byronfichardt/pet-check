"""Loudness-gated sound event detection.

This mirrors app/src/main/java/.../audio/EventDetector.kt. Both implementations are
checked against testdata/detector_cases.json, so change them together.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

SILENCE_DB = -96.0


def rms_db(samples) -> float:
    """Level of 16-bit PCM samples in dBFS (0 is full scale)."""
    n = len(samples)
    if n == 0:
        return SILENCE_DB
    total = 0.0
    for s in samples:
        v = s / 32768.0
        total += v * v
    rms = math.sqrt(total / n)
    if rms <= 0.0:
        return SILENCE_DB
    return max(SILENCE_DB, 20.0 * math.log10(rms))


def margin_for_sensitivity(sensitivity: int) -> float:
    """Maps the 0-100 sensitivity setting to dB above the noise floor (30 dB down to 8 dB)."""
    s = min(100, max(0, sensitivity))
    return 30.0 - s * 0.22


@dataclass(frozen=True)
class DetectorConfig:
    margin_db: float = 19.0          # how far above the noise floor counts as loud
    min_trigger_db: float = -50.0    # never trigger below this, however quiet the room
    max_trigger_db: float = -6.0     # never require more than this, however noisy the room
    attack_ms: int = 100             # must stay loud this long before an event starts
    hold_ms: int = 2000              # quiet gap that ends an event
    max_event_ms: int = 120_000      # longer noise is split into several events
    floor_rise_ms: int = 10_000      # noise floor adapts slowly to louder surroundings
    floor_fall_ms: int = 1000        # and quickly to quieter ones
    min_floor_db: float = -80.0
    max_floor_db: float = -30.0


@dataclass(frozen=True)
class DetectedEvent:
    start_ms: int
    duration_ms: int
    peak_db: float


class EventDetector:
    def __init__(self, config: DetectorConfig | None = None):
        self.config = config or DetectorConfig()
        self._floor: float | None = None
        self._active = False
        self._start_ms = 0
        self._last_loud_end_ms = 0
        self._peak_db = SILENCE_DB
        self._attack_start_ms = -1
        self._attack_peak_db = SILENCE_DB

    @property
    def active(self) -> bool:
        return self._active

    @property
    def noise_floor_db(self) -> float:
        return self.config.min_floor_db if self._floor is None else self._floor

    @property
    def threshold_db(self) -> float:
        c = self.config
        return min(c.max_trigger_db, max(c.min_trigger_db, self.noise_floor_db + c.margin_db))

    def ms_since_loud(self, now_ms: int) -> int:
        return now_ms - self._last_loud_end_ms

    def process(self, frame_start_ms: int, frame_ms: int, level_db: float):
        """Feed one frame. Returns ("started", start_ms), ("ended", DetectedEvent) or None."""
        frame_end = frame_start_ms + frame_ms
        if self._floor is None:
            self._floor = self._clamp_floor(level_db)
        loud = level_db >= self.threshold_db

        if not self._active:
            if not loud:
                self._attack_start_ms = -1
                self._update_floor(level_db, frame_ms)
                return None
            if self._attack_start_ms < 0:
                self._attack_start_ms = frame_start_ms
                self._attack_peak_db = level_db
            else:
                self._attack_peak_db = max(self._attack_peak_db, level_db)
            if frame_end - self._attack_start_ms < self.config.attack_ms:
                return None
            self._active = True
            self._start_ms = self._attack_start_ms
            self._peak_db = self._attack_peak_db
            self._last_loud_end_ms = frame_end
            self._attack_start_ms = -1
            return ("started", self._start_ms)

        if loud:
            self._peak_db = max(self._peak_db, level_db)
            self._last_loud_end_ms = frame_end
        if (frame_end - self._last_loud_end_ms >= self.config.hold_ms
                or frame_end - self._start_ms >= self.config.max_event_ms):
            return ("ended", self._finish())
        return None

    def flush(self) -> DetectedEvent | None:
        """Ends the event in progress, if any. Call when monitoring stops."""
        self._attack_start_ms = -1
        return self._finish() if self._active else None

    def _finish(self) -> DetectedEvent:
        self._active = False
        return DetectedEvent(self._start_ms, self._last_loud_end_ms - self._start_ms, self._peak_db)

    def _update_floor(self, level_db: float, frame_ms: int) -> None:
        c = self.config
        tau = c.floor_rise_ms if level_db > self._floor else c.floor_fall_ms
        alpha = min(1.0, frame_ms / tau)
        self._floor = self._clamp_floor(self._floor + alpha * (level_db - self._floor))

    def _clamp_floor(self, value: float) -> float:
        return min(self.config.max_floor_db, max(self.config.min_floor_db, value))
