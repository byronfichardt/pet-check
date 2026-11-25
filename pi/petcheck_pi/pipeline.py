"""Turns a stream of 16 kHz PCM frames into finished, classified sound events.

Mirrors app/src/main/java/.../audio/MonitorPipeline.kt.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from .classify import SoundTypeMapper
from .detector import SILENCE_DB, DetectorConfig, EventDetector

SAMPLE_RATE = 16_000
FRAME_SAMPLES = 800                 # 50 ms
WINDOW_SAMPLES = 15_600             # one YAMNet window, 0.975 s
FIRST_INFERENCE_SAMPLES = 4_800     # classify 0.3 s into an event, so short barks are caught
HOP_SAMPLES = 7_800                 # then every half window
INFERENCE_QUIET_LIMIT_MS = 1_000    # stop classifying once the event has gone quiet


@dataclass(frozen=True)
class FinishedEvent:
    start_ms: int
    duration_ms: int
    peak_db: float
    type: str
    confidence: float


@dataclass(frozen=True)
class FrameResult:
    level_db: float
    threshold_db: float
    active: bool
    finished: FinishedEvent | None


def frame_level_db(frame: np.ndarray) -> float:
    """Level of an int16 frame in dBFS. Same result as detector.rms_db, but fast."""
    if frame.size == 0:
        return SILENCE_DB
    scaled = frame.astype(np.float64) / 32768.0
    rms = math.sqrt(float(np.mean(scaled * scaled)))
    return SILENCE_DB if rms <= 0.0 else max(SILENCE_DB, 20.0 * math.log10(rms))


class MonitorPipeline:
    """Not thread-safe: feed it from one thread.

    Audio only ever lives in the ring buffer here: under a second of it, overwritten as it goes.
    """

    def __init__(self, classifier, mapper: SoundTypeMapper, config: DetectorConfig | None = None,
                 sample_rate: int = SAMPLE_RATE):
        self._classifier = classifier
        self._mapper = mapper
        self._detector = EventDetector(config)
        self._sample_rate = sample_rate
        self._ring = np.zeros(WINDOW_SAMPLES, dtype=np.float32)
        self._ring_pos = 0
        self._accumulator = mapper.accumulator()
        self._until_inference = 0

    @property
    def config(self) -> DetectorConfig:
        return self._detector.config

    @config.setter
    def config(self, value: DetectorConfig) -> None:
        self._detector.config = value

    def on_frame(self, frame: np.ndarray, frame_start_ms: int) -> FrameResult:
        """frame: a one-dimensional int16 array of at most WINDOW_SAMPLES samples."""
        n = int(frame.size)
        frame_ms = n * 1000 // self._sample_rate
        level = frame_level_db(frame)
        self._write_ring(frame)

        signal = self._detector.process(frame_start_ms, frame_ms, level)
        finished = None
        if signal is not None and signal[0] == "started":
            self._accumulator = self._mapper.accumulator()
            self._until_inference = FIRST_INFERENCE_SAMPLES
        elif signal is not None and signal[0] == "ended":
            finished = self._finish(signal[1])

        if self._detector.active and self._classifier is not None:
            self._until_inference -= n
            if self._until_inference <= 0:
                self._until_inference = HOP_SAMPLES
                if self._detector.ms_since_loud(frame_start_ms + frame_ms) <= INFERENCE_QUIET_LIMIT_MS:
                    self._accumulator.add(self._classifier.classify(self._window()))

        return FrameResult(level, self._detector.threshold_db, self._detector.active, finished)

    def flush(self) -> FinishedEvent | None:
        event = self._detector.flush()
        return self._finish(event) if event is not None else None

    def _finish(self, event) -> FinishedEvent:
        kind, confidence = self._accumulator.result()
        return FinishedEvent(event.start_ms, event.duration_ms, event.peak_db, kind, confidence)

    def _write_ring(self, frame: np.ndarray) -> None:
        samples = frame.astype(np.float32) / 32768.0
        n = samples.size
        end = self._ring_pos + n
        if end <= WINDOW_SAMPLES:
            self._ring[self._ring_pos:end] = samples
        else:
            split = WINDOW_SAMPLES - self._ring_pos
            self._ring[self._ring_pos:] = samples[:split]
            self._ring[:n - split] = samples[split:]
        self._ring_pos = end % WINDOW_SAMPLES

    def _window(self) -> np.ndarray:
        """The last WINDOW_SAMPLES samples, oldest first."""
        return np.concatenate((self._ring[self._ring_pos:], self._ring[:self._ring_pos]))
