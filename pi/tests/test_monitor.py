import logging
import os
import tempfile
import unittest
import wave
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

from petcheck_pi.alerts import AlertRule
from petcheck_pi.classify import SoundTypeMapper
from petcheck_pi.config import Config, State, load_config
from petcheck_pi.firebase import SERVER_TIME, FirebaseError, FirebaseSession, encode_value, timestamp_ms
from petcheck_pi.monitor import Monitor, read_wav, run_wav
from petcheck_pi.store import EventStore, StoredEvent

LABELS = ["Dog", "Bark", "Yip", "Howl", "Bow-wow", "Growling", "Whimper (dog)", "Cat", "Meow", "Caterwaul"]


class BarkClassifier:
    def classify(self, window):
        out = np.zeros(len(LABELS), dtype=np.float32)
        out[LABELS.index("Bark")] = 0.9
        return out


class FakeSession(FirebaseSession):
    """Records commits instead of sending them."""

    def __init__(self):
        super().__init__("key", "demo-project", "refresh")
        self.uid = "alice"
        self.commits = []
        self.fail = False

    def commit(self, writes):
        if self.fail:
            raise FirebaseError("network error: offline")
        self.commits.append(writes)


def write_wav(path, seconds=1.0, rate=16000, channels=1, amplitude=12000):
    tone = (np.sin(2 * np.pi * 700 * np.arange(int(rate * seconds)) / rate) * amplitude).astype(np.int16)
    data = np.repeat(tone, channels)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(channels)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(data.tobytes())


class MonitorTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.wav = Path(self.dir.name) / "tone.wav"
        write_wav(self.wav)
        self.config = Config(device_name="Test Pi", state_dir=Path(self.dir.name))
        self.store = EventStore(":memory:")
        self.addCleanup(self.store.close)
        self.mapper = SoundTypeMapper(LABELS)
        logging.disable(logging.CRITICAL)
        self.addCleanup(logging.disable, logging.NOTSET)

    def monitor(self, session=None, **overrides):
        for key, value in overrides.items():
            setattr(self.config, key, value)
        return Monitor(self.config, self.store, "device-1", BarkClassifier(), self.mapper, session)

    def test_a_recording_becomes_one_stored_event(self):
        monitor = self.monitor()
        run_wav(monitor, str(self.wav), end_ms=1_800_000_000_000)
        events = self.store.recent()
        self.assertEqual(len(events), 1)
        event = events[0]
        self.assertEqual((event.type, event.duration_ms, event.synced), ("bark", 1000, False))
        # 2 s lead-in + 1 s tone + 4 s tail: the tone starts 5 s before the end.
        self.assertEqual(event.started_at_ms, 1_800_000_000_000 - 5000)
        self.assertAlmostEqual(event.peak_db, -11.7, delta=0.5)
        self.assertTrue(monitor.sync())   # nothing to do without a session

    def test_events_upload_once_and_carry_only_metadata(self):
        session = FakeSession()
        monitor = self.monitor(session)
        run_wav(monitor, str(self.wav), end_ms=1_800_000_000_000)
        self.assertTrue(monitor.sync())
        self.assertTrue(monitor.sync())

        device, events = session.commits[0], session.commits[1]
        self.assertEqual(len(session.commits), 2)   # the second sync had nothing new to send
        self.assertIn("users/alice/devices/device-1", device[0]["update"]["name"])
        self.assertEqual(device[0]["update"]["fields"]["platform"], {"stringValue": "pi"})
        self.assertEqual(device[0]["update"]["fields"]["monitoring"], {"booleanValue": True})

        write = events[0]
        self.assertIn("users/alice/events/", write["update"]["name"])
        self.assertEqual(
            sorted(write["update"]["fields"]),
            ["confidence", "deviceId", "deviceName", "durationMs", "peakDb", "startedAt", "type"],
        )
        self.assertEqual(write["update"]["fields"]["durationMs"], {"integerValue": "1000"})
        self.assertEqual(write["update"]["fields"]["type"], {"stringValue": "bark"})
        self.assertEqual(write["updateTransforms"], [{"fieldPath": "syncedAt", "setToServerValue": "REQUEST_TIME"}])
        self.assertNotIn("updateMask", write)
        self.assertEqual(self.store.unsynced(), [])

    def test_events_wait_while_offline_and_upload_later(self):
        session = FakeSession()
        session.fail = True
        monitor = self.monitor(session)
        run_wav(monitor, str(self.wav), end_ms=1_800_000_000_000)
        self.assertFalse(monitor.sync())
        self.assertEqual(len(self.store.unsynced()), 1)
        session.fail = False
        self.assertTrue(monitor.sync())
        self.assertEqual(self.store.unsynced(), [])

    def test_alerts_are_sent_and_stopping_is_reported(self):
        session = FakeSession()
        monitor = self.monitor(session, alerts=AlertRule(enabled=True, noisy_seconds=1, window_minutes=10))
        run_wav(monitor, str(self.wav), end_ms=1_800_000_000_000)
        monitor.sync()
        alert = session.commits[-1][0]
        self.assertIn("users/alice/alerts/", alert["update"]["name"])
        self.assertEqual(alert["update"]["fields"]["noisySeconds"], {"integerValue": "1"})
        self.assertEqual(alert["updateTransforms"][0]["fieldPath"], "createdAt")

        monitor.sync(monitoring=False, force_heartbeat=True)
        stopped = session.commits[-1][0]
        self.assertEqual(stopped["update"]["fields"]["monitoring"], {"booleanValue": False})
        self.assertIn("monitoringSince", stopped["updateMask"]["fieldPaths"])
        self.assertNotIn("monitoringSince", stopped["update"]["fields"])

    def test_other_sounds_can_be_left_out(self):
        class Silent:
            def classify(self, window):
                return np.zeros(len(LABELS), dtype=np.float32)

        self.config.log_other_sounds = False
        quiet = Monitor(self.config, self.store, "device-1", Silent(), self.mapper)
        run_wav(quiet, str(self.wav))
        self.assertEqual(self.store.recent(), [])

    def test_wav_files_are_converted_to_16k_mono(self):
        stereo = Path(self.dir.name) / "stereo.wav"
        write_wav(stereo, seconds=0.5, rate=44100, channels=2)
        samples = read_wav(str(stereo))
        self.assertEqual(samples.dtype, np.int16)
        self.assertEqual(samples.size, 8000)
        self.assertGreater(np.abs(samples).max(), 10000)


class StoreTests(unittest.TestCase):
    def test_round_trip_sync_flags_and_pruning(self):
        store = EventStore(":memory:")
        self.addCleanup(store.close)
        store.add(StoredEvent("a", 1000, 500, "bark", -12.0, 0.8))
        store.add(StoredEvent("b", 2000, 700, "howl", -9.5, 0.6))
        self.assertEqual([e.id for e in store.unsynced()], ["a", "b"])
        self.assertEqual([e.id for e in store.recent()], ["b", "a"])
        store.mark_synced(["a"])
        self.assertEqual([e.id for e in store.unsynced()], ["b"])
        self.assertEqual(store.prune(older_than_ms=5000), 1)   # only the uploaded one goes
        self.assertEqual([e.id for e in store.recent()], ["b"])


class FirebaseEncodingTests(unittest.TestCase):
    def test_values(self):
        self.assertEqual(encode_value(True), {"booleanValue": True})
        self.assertEqual(encode_value(42), {"integerValue": "42"})
        self.assertEqual(encode_value(-12.5), {"doubleValue": -12.5})
        self.assertEqual(encode_value("bark"), {"stringValue": "bark"})
        self.assertEqual(encode_value(timestamp_ms(1_800_000_000_123)), {"timestampValue": "2027-01-15T08:00:00.123Z"})
        self.assertEqual(timestamp_ms(0), datetime(1970, 1, 1, tzinfo=timezone.utc))
        with self.assertRaises(TypeError):
            encode_value(None)

    def test_merge_writes_touch_only_named_fields(self):
        session = FirebaseSession("key", "demo", "refresh")
        write = session.build_write("users/u/devices/d", {"monitoring": True, "lastSeen": SERVER_TIME}, merge=True)
        self.assertEqual(write["update"]["name"], "projects/demo/databases/(default)/documents/users/u/devices/d")
        self.assertEqual(write["updateMask"], {"fieldPaths": ["monitoring"]})
        self.assertEqual(write["updateTransforms"], [{"fieldPath": "lastSeen", "setToServerValue": "REQUEST_TIME"}])


class ConfigTests(unittest.TestCase):
    def test_file_values_and_placeholders(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.toml"
            path.write_text(
                '[monitor]\ndevice_name = "Hall"\nsensitivity = 250\nlog_other_sounds = false\n'
                '[alerts]\nenabled = true\nnoisy_seconds = 30\n'
                '[firebase]\nproject_id = "your-project-id"\napi_key = "your-web-api-key"\n'
            )
            config = load_config(path)
            self.assertEqual((config.device_name, config.sensitivity, config.log_other_sounds), ("Hall", 100, False))
            self.assertEqual((config.alerts.enabled, config.alerts.noisy_seconds, config.alerts.window_minutes), (True, 30, 10))
            self.assertFalse(config.remote_configured)   # the example placeholders are not a real project
            with self.assertRaises(FileNotFoundError):
                load_config(Path(directory) / "missing.toml")

    def test_state_keeps_the_device_id_and_protects_the_token(self):
        with tempfile.TemporaryDirectory() as directory:
            state = State(Path(directory))
            self.assertEqual(state.device_id(), state.device_id())
            self.assertIsNone(state.load_refresh_token())
            state.save_refresh_token("secret")
            self.assertEqual(state.load_refresh_token(), "secret")
            self.assertEqual(os.stat(Path(directory) / "credentials.json").st_mode & 0o777, 0o600)
            state.clear_refresh_token()
            self.assertIsNone(state.load_refresh_token())


if __name__ == "__main__":
    unittest.main()
