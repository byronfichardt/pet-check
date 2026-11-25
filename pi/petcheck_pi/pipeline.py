"""Turns a stream of 16 kHz PCM frames into finished, classified sound events.

Mirrors app/src/main/java/.../audio/MonitorPipeline.kt.
"""

from __future__ import annotations

from dataclasses import dataclass

from .classify import SoundTypeMapper
from .detector import DetectorConfig, EventDetector, rms_db

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


class MonitorPipeline:
    def __init__(self, classifier, mapper: SoundTypeMapper, config: DetectorConfig | None = None,
                 sample_rate: int = SAMPLE_RATE):
        self._classifier = classifier
        self._mapper = mapper
        self._detector = EventDetector(config)
        self._sample_rate = sample_rate
        self._ring = [0.0] * WINDOW_SAMPLES
        self._ring_pos = 0
        self._accumulator = mapper.accumulator()
        self._until_inference = 0

    def on_frame(self, samples, frame_start_ms: int) -> FrameResult:
        """samples: a sequence of 16-bit PCM values."""
        n = len(samples)
        frame_ms = n * 1000 // self._sample_rate
        level = rms_db(samples)
        self._write_ring(samples)

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
                frame_end = frame_start_ms + frame_ms
                if self._detector.ms_since_loud(frame_end) <= INFERENCE_QUIET_LIMIT_MS:
                    self._accumulator.add(self._classifier.classify(self._window()))

        return FrameResult(level, self._detector.threshold_db, self._detector.active, finished)

    def flush(self) -> FinishedEvent | None:
        event = self._detector.flush()
        return self._finish(event) if event is not None else None

    def _finish(self, event) -> FinishedEvent:
        kind, confidence = self._accumulator.result()
        return FinishedEvent(event.start_ms, event.duration_ms, event.peak_db, kind, confidence)

    def _write_ring(self, samples) -> None:
        ring, pos = self._ring, self._ring_pos
        for s in samples:
            ring[pos] = s / 32768.0
            pos += 1
            if pos == WINDOW_SAMPLES:
                pos = 0
        self._ring_pos = pos

    def _window(self) -> list[float]:
        return self._ring[self._ring_pos:] + self._ring[:self._ring_pos]
