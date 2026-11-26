"""Runs the real sound model on a real bark. Skipped where the model runtime is not installed."""

import unittest
from pathlib import Path

import numpy as np

from petcheck_pi.classify import SoundTypeMapper, load_labels
from petcheck_pi.monitor import read_wav
from petcheck_pi.pipeline import FRAME_SAMPLES, MonitorPipeline

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "app" / "src" / "main" / "assets"
BARK = REPO / "testdata" / "dog_bark.wav"

try:
    from ai_edge_litert.interpreter import Interpreter  # noqa: F401
    HAVE_RUNTIME = True
except ImportError:
    HAVE_RUNTIME = False


@unittest.skipUnless(HAVE_RUNTIME, "ai-edge-litert is not installed")
class RealModelTests(unittest.TestCase):
    """The Android app runs the same checks on a device in ClassifierDeviceTest.kt."""

    @classmethod
    def setUpClass(cls):
        from petcheck_pi.classify import YamnetClassifier

        cls.labels = load_labels(str(ASSETS / "yamnet_label_list.txt"))
        cls.classifier = YamnetClassifier(str(ASSETS / "yamnet.tflite"))

    def test_model_scores_every_label_and_knows_silence(self):
        self.assertEqual(len(self.labels), 521)
        scores = self.classifier.classify(np.zeros(15600, dtype=np.float32))
        self.assertEqual(scores.size, 521)
        self.assertGreater(scores[self.labels.index("Silence")], 0.5)

    def test_the_whole_pipeline_logs_the_bark_with_its_time_and_length(self):
        recording = read_wav(str(BARK))
        pipeline = MonitorPipeline(self.classifier, SoundTypeMapper(self.labels))
        events = []
        for offset in range(0, recording.size - FRAME_SAMPLES + 1, FRAME_SAMPLES):
            result = pipeline.on_frame(recording[offset:offset + FRAME_SAMPLES], offset * 1000 // 16000)
            if result.finished:
                events.append(result.finished)
        last = pipeline.flush()
        if last:
            events.append(last)

        self.assertEqual(len(events), 1, events)
        event = events[0]
        self.assertEqual((event.type, event.start_ms, event.duration_ms), ("bark", 2250, 300))
        self.assertAlmostEqual(event.peak_db, -10.7, delta=0.2)
        self.assertGreater(event.confidence, 0.5)


if __name__ == "__main__":
    unittest.main()
