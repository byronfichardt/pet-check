"""The monitor itself: frames in, logged and uploaded events out."""

from __future__ import annotations

import logging
import platform
import queue
import threading
import time
import uuid
import wave
from datetime import datetime

import numpy as np

from . import __version__
from .alerts import AlertEvaluator, AlertTrigger
from .classify import SoundTypeMapper
from .config import Config
from .detector import DetectorConfig, margin_for_sensitivity
from .firebase import SERVER_TIME, FirebaseError, FirebaseSession, timestamp_ms
from .pipeline import FRAME_SAMPLES, SAMPLE_RATE, FinishedEvent, MonitorPipeline
from .store import EventStore, StoredEvent

log = logging.getLogger("petcheck")

HEARTBEAT_SECONDS = 10 * 60
SYNC_SECONDS = 5
UPLOAD_BATCH = 200
MAX_CLOCK_DRIFT_MS = 1000
KEEP_UPLOADED_DAYS = 30


class Monitor:
    def __init__(self, config: Config, store: EventStore, device_id: str, classifier, mapper: SoundTypeMapper,
                 session: FirebaseSession | None = None):
        self.config = config
        self.store = store
        self.device_id = device_id
        self.session = session
        self.pipeline = MonitorPipeline(
            classifier, mapper, DetectorConfig(margin_db=margin_for_sensitivity(config.sensitivity))
        )
        self.event_count = 0
        self._alerts = AlertEvaluator()
        self._pending_alerts: queue.Queue[AlertTrigger] = queue.Queue()
        self._last_heartbeat = 0.0
        self._announced = False

    # --- Audio side: called from the capture thread ---

    def handle_frame(self, frame: np.ndarray, frame_start_ms: int) -> None:
        result = self.pipeline.on_frame(frame, frame_start_ms)
        if result.finished is not None:
            self._record(result.finished)

    def finish(self) -> None:
        """Ends the event in progress, if any."""
        last = self.pipeline.flush()
        if last is not None:
            self._record(last)

    def _record(self, event: FinishedEvent) -> None:
        if event.type == "other" and not self.config.log_other_sounds:
            return
        self.store.add(StoredEvent(str(uuid.uuid4()), event.start_ms, event.duration_ms, event.type,
                                   float(event.peak_db), float(event.confidence)))
        self.event_count += 1
        started = datetime.fromtimestamp(event.start_ms / 1000).strftime("%Y-%m-%d %H:%M:%S")
        log.info("%s  %-5s  %5.1f s  peak %4.0f dB  confidence %.2f",
                 started, event.type, event.duration_ms / 1000, event.peak_db, event.confidence)
        trigger = self._alerts.on_event(self.config.alerts, event.start_ms, event.duration_ms, event.type)
        if trigger is not None:
            log.info("alert: %d s of noise in the last %d min", trigger.noisy_seconds, trigger.window_minutes)
            self._pending_alerts.put(trigger)

    # --- Network side: called from the sync thread, never from the capture thread ---

    def sync(self, monitoring: bool = True, force_heartbeat: bool = False) -> bool:
        """Uploads waiting events and alerts and reports status. Returns False if the network failed."""
        session = self.session
        if session is None:
            return True
        try:
            now = time.time()
            if force_heartbeat or not self._announced or now - self._last_heartbeat >= HEARTBEAT_SECONDS:
                session.commit([self._device_write(monitoring)])
                self._announced = True
                self._last_heartbeat = now

            while True:
                pending = self.store.unsynced(UPLOAD_BATCH)
                if not pending:
                    break
                session.commit([self._event_write(event) for event in pending])
                self.store.mark_synced([event.id for event in pending])

            while True:
                try:
                    trigger = self._pending_alerts.get_nowait()
                except queue.Empty:
                    break
                try:
                    session.commit([self._alert_write(trigger)])
                except FirebaseError:
                    self._pending_alerts.put(trigger)
                    raise
            return True
        except FirebaseError as error:
            log.warning("could not reach Firebase, will retry: %s", error)
            return False

    def _device_write(self, monitoring: bool) -> dict:
        fields = {
            "name": self.config.device_name,
            "platform": "pi",
            "model": f"{platform.node()} ({platform.machine()})"[:80],
            "appVersion": __version__,
            "monitoring": monitoring,
            "lastSeen": SERVER_TIME,
        }
        delete: tuple[str, ...] = ()
        if not self._announced and monitoring:
            fields["monitoringSince"] = SERVER_TIME
        elif not monitoring:
            delete = ("monitoringSince",)
        return self.session.build_write(
            self.session.user_path("devices", self.device_id), fields, merge=True, delete=delete
        )

    def _event_write(self, event: StoredEvent) -> dict:
        return self.session.build_write(
            self.session.user_path("events", event.id),
            {
                "deviceId": self.device_id,
                "deviceName": self.config.device_name,
                "startedAt": timestamp_ms(event.started_at_ms),
                "durationMs": int(event.duration_ms),
                "type": event.type,
                "peakDb": float(event.peak_db),
                "confidence": float(event.confidence),
                "syncedAt": SERVER_TIME,
            },
        )

    def _alert_write(self, trigger: AlertTrigger) -> dict:
        return self.session.build_write(
            self.session.user_path("alerts", str(uuid.uuid4())),
            {
                "deviceId": self.device_id,
                "deviceName": self.config.device_name,
                "createdAt": SERVER_TIME,
                "noisySeconds": int(trigger.noisy_seconds),
                "windowMinutes": int(trigger.window_minutes),
                "type": trigger.type,
            },
        )


class SyncThread(threading.Thread):
    """Keeps uploads off the capture thread, so a slow network never drops audio."""

    def __init__(self, monitor: Monitor):
        super().__init__(name="petcheck-sync", daemon=True)
        self._monitor = monitor
        self._stop_event = threading.Event()

    def run(self) -> None:
        last_prune = 0.0
        while not self._stop_event.is_set():
            self._monitor.sync()
            if time.time() - last_prune > 24 * 3600:
                cutoff = int((time.time() - KEEP_UPLOADED_DAYS * 24 * 3600) * 1000)
                self._monitor.store.prune(cutoff)
                last_prune = time.time()
            self._stop_event.wait(SYNC_SECONDS)

    def stop(self) -> None:
        self._stop_event.set()
        self.join(timeout=30)


def read_wav(path: str) -> np.ndarray:
    """Loads a WAV file as 16 kHz mono int16, converting if it has to."""
    with wave.open(path, "rb") as handle:
        if handle.getsampwidth() != 2:
            raise ValueError("only 16-bit WAV files are supported")
        rate = handle.getframerate()
        channels = handle.getnchannels()
        samples = np.frombuffer(handle.readframes(handle.getnframes()), dtype=np.int16)
    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1)
    if rate != SAMPLE_RATE:
        duration = samples.size / rate
        target = np.arange(int(duration * SAMPLE_RATE)) / SAMPLE_RATE
        samples = np.interp(target, np.arange(samples.size) / rate, samples)
    return np.clip(np.round(samples), -32768, 32767).astype(np.int16)


def run_wav(monitor: Monitor, path: str, end_ms: int | None = None) -> None:
    """Feeds a recording through the monitor as if it had just been heard. For testing a setup."""
    recording = read_wav(path)
    # A live monitor has always heard the room before a sound starts; give the file the same lead-in.
    rng = np.random.default_rng(0)
    room = rng.normal(0, 12, SAMPLE_RATE * 2).astype(np.int16)
    samples = np.concatenate((room, recording, room, room))
    end = end_ms if end_ms is not None else int(time.time() * 1000)
    start = end - samples.size * 1000 // SAMPLE_RATE
    for offset in range(0, samples.size - FRAME_SAMPLES + 1, FRAME_SAMPLES):
        monitor.handle_frame(samples[offset:offset + FRAME_SAMPLES], start + offset * 1000 // SAMPLE_RATE)
    monitor.finish()


def run_microphone(monitor: Monitor, stop: threading.Event, device: str | None = None) -> None:
    """Listens until stop is set. Frames are timed by sample count, re-anchored if the clock drifts."""
    import sounddevice

    frames: queue.Queue[bytes] = queue.Queue(maxsize=400)   # 20 s of headroom

    def on_audio(data, frame_count, time_info, status):
        if status:
            log.debug("audio status: %s", status)
        try:
            frames.put_nowait(bytes(data))
        except queue.Full:
            pass   # the monitor has fallen far behind: drop audio rather than grow without bound

    anchor_ms = int(time.time() * 1000)
    samples_since_anchor = 0
    frame_ms = FRAME_SAMPLES * 1000 // SAMPLE_RATE
    with sounddevice.RawInputStream(samplerate=SAMPLE_RATE, blocksize=FRAME_SAMPLES, channels=1,
                                    dtype="int16", device=device, callback=on_audio):
        log.info("listening as %r (sensitivity %d)", monitor.config.device_name, monitor.config.sensitivity)
        while not stop.is_set():
            try:
                data = frames.get(timeout=0.5)
            except queue.Empty:
                continue
            frame = np.frombuffer(data, dtype=np.int16)
            frame_start = anchor_ms + samples_since_anchor * 1000 // SAMPLE_RATE
            wall_start = int(time.time() * 1000) - frame_ms
            if abs(wall_start - frame_start) > MAX_CLOCK_DRIFT_MS and frames.empty():
                anchor_ms, samples_since_anchor, frame_start = wall_start, 0, wall_start
            samples_since_anchor += frame.size
            monitor.handle_frame(frame, frame_start)
    monitor.finish()
