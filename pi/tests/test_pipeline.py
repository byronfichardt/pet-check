import unittest

import numpy as np

from petcheck_pi.classify import MIN_CONFIDENCE, SoundTypeMapper
from petcheck_pi.detector import rms_db
from petcheck_pi.pipeline import FRAME_SAMPLES, WINDOW_SAMPLES, MonitorPipeline, frame_level_db

LABELS = ["Speech", "Dog", "Bark", "Yip", "Howl", "Bow-wow", "Growling", "Whimper (dog)", "Cat", "Meow",
          "Caterwaul", "Silence"]
QUIET = np.array([8, -8] * (FRAME_SAMPLES // 2), dtype=np.int16)
LOUD = (np.sin(2 * np.pi * 800 * np.arange(FRAME_SAMPLES) / 16000) * 12000).astype(np.int16)


def scores(**values):
    out = np.zeros(len(LABELS), dtype=np.float32)
    for label, score in values.items():
        out[LABELS.index(label.replace("_", " "))] = score
    return out


class FakeClassifier:
    def __init__(self, *results):
        self.results = list(results)
        self.windows = []

    def classify(self, window):
        self.windows.append(np.array(window))
        return self.results[min(len(self.windows) - 1, len(self.results) - 1)]


def run(pipeline, *plan):
    events, time_ms = [], 0
    for frame, count in plan:
        for _ in range(count):
            result = pipeline.on_frame(frame, time_ms)
            if result.finished:
                events.append(result.finished)
            time_ms += 50
    last = pipeline.flush()
    if last:
        events.append(last)
    return events


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.mapper = SoundTypeMapper(LABELS)

    def test_a_bark_is_detected_measured_and_classified(self):
        classifier = FakeClassifier(scores(Dog=0.8, Bark=0.7))
        events = run(MonitorPipeline(classifier, self.mapper), (QUIET, 40), (LOUD, 20), (QUIET, 60))
        self.assertEqual(len(events), 1)
        event = events[0]
        self.assertEqual((event.start_ms, event.duration_ms, event.type), (2000, 1000, "bark"))
        self.assertAlmostEqual(event.confidence, 0.7, places=6)
        self.assertAlmostEqual(event.peak_db, -11.5, delta=0.5)
        first = classifier.windows[0]
        self.assertEqual(first.size, WINDOW_SAMPLES)
        self.assertTrue((np.abs(first[-400:]) > 0.2).any())    # newest audio is the loud tone
        self.assertTrue((np.abs(first[:400]) < 0.01).all())    # oldest is the quiet room
        self.assertIn(len(classifier.windows), range(2, 6))

    def test_a_very_short_sound_is_still_classified(self):
        classifier = FakeClassifier(scores(Meow=0.9))
        events = run(MonitorPipeline(classifier, self.mapper), (QUIET, 40), (LOUD, 3), (QUIET, 60))
        self.assertEqual([(e.duration_ms, e.type) for e in events], [(150, "meow")])
        self.assertEqual(len(classifier.windows), 2)

    def test_the_classifier_is_not_run_while_the_room_is_quiet(self):
        classifier = FakeClassifier(scores())
        self.assertEqual(run(MonitorPipeline(classifier, self.mapper), (QUIET, 400)), [])
        self.assertEqual(classifier.windows, [])

    def test_unrecognised_and_unclassified_sounds_are_other(self):
        unsure = FakeClassifier(scores(Speech=0.9, Bark=0.1))
        self.assertEqual(run(MonitorPipeline(unsure, self.mapper), (QUIET, 40), (LOUD, 10), (QUIET, 60))[0].type, "other")
        no_model = run(MonitorPipeline(None, self.mapper), (QUIET, 40), (LOUD, 10), (QUIET, 60))[0]
        self.assertEqual((no_model.type, no_model.confidence), ("other", 0.0))

    def test_each_event_is_classified_on_its_own(self):
        classifier = FakeClassifier(scores(Howl=0.9), scores(Howl=0.9), scores(Bark=0.6))
        events = run(MonitorPipeline(classifier, self.mapper),
                     (QUIET, 40), (LOUD, 10), (QUIET, 80), (LOUD, 10), (QUIET, 60))
        self.assertEqual([e.type for e in events], ["howl", "bark"])

    def test_fast_level_matches_the_reference(self):
        for frame in (QUIET, LOUD, np.zeros(FRAME_SAMPLES, dtype=np.int16)):
            self.assertAlmostEqual(frame_level_db(frame), rms_db(frame.tolist()), places=9)


class MapperTests(unittest.TestCase):
    def setUp(self):
        self.mapper = SoundTypeMapper(LABELS)

    def test_specific_labels_win_and_generic_ones_are_the_fallback(self):
        specific = self.mapper.accumulator()
        specific.add(scores(Dog=0.9, Howl=0.5, Bark=0.4))
        self.assertEqual(specific.result()[0], "howl")
        self.assertAlmostEqual(specific.result()[1], 0.5, places=6)

        generic = self.mapper.accumulator()
        generic.add(scores(Cat=0.6, Meow=0.1))
        self.assertEqual(generic.result()[0], "meow")

        peak = self.mapper.accumulator()
        peak.add(scores(Growling=0.2))
        peak.add(scores(Growling=0.8))
        peak.add(scores(**{"Whimper_(dog)": 0.5}))
        self.assertEqual(peak.result()[0], "growl")
        self.assertEqual(peak.windows, 3)
        self.assertEqual(self.mapper.accumulator().result(), ("other", 0.0))

    def test_threshold_matches_the_android_app(self):
        self.assertEqual(MIN_CONFIDENCE, 0.35)

    def test_a_label_list_without_the_pet_labels_is_rejected(self):
        with self.assertRaises(ValueError):
            SoundTypeMapper(["Speech", "Music"])


if __name__ == "__main__":
    unittest.main()
