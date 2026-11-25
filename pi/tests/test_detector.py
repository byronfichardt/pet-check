import unittest
from dataclasses import replace
from pathlib import Path

from petcheck_pi.detector import DetectorConfig, EventDetector, margin_for_sensitivity, rms_db

CASES = Path(__file__).resolve().parents[2] / "testdata" / "detector_cases.txt"
FRAME_MS = 50


def load_cases():
    cases, current = [], None
    for raw in CASES.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        keyword, _, rest = line.partition(" ")
        if keyword == "case":
            current = {"name": rest, "config": {}, "frames": [], "expect": []}
            cases.append(current)
        elif keyword == "config":
            for pair in rest.split():
                key, value = pair.split("=")
                current["config"][key] = float(value) if "db" in key else int(value)
        elif keyword == "frames":
            for chunk in rest.split():
                level, count = chunk.split("x")
                current["frames"] += [float(level)] * int(count)
        elif keyword == "expect":
            start, duration, peak = rest.split()
            current["expect"].append((int(start), int(duration), float(peak)))
        else:
            raise ValueError(f"unknown line: {line}")
    return cases


class SharedDetectorCases(unittest.TestCase):
    def test_cases(self):
        cases = load_cases()
        self.assertGreaterEqual(len(cases), 10)
        for case in cases:
            with self.subTest(case["name"]):
                detector = EventDetector(replace(DetectorConfig(), **case["config"]))
                events = []
                for index, level in enumerate(case["frames"]):
                    signal = detector.process(index * FRAME_MS, FRAME_MS, level)
                    if signal and signal[0] == "ended":
                        events.append(signal[1])
                last = detector.flush()
                if last:
                    events.append(last)
                self.assertEqual([(e.start_ms, e.duration_ms, e.peak_db) for e in events], case["expect"])


class LevelTests(unittest.TestCase):
    def test_full_scale_square_wave_is_zero_db(self):
        self.assertAlmostEqual(rms_db([32767, -32768] * 100), 0.0, places=2)

    def test_half_scale_is_minus_six_db(self):
        self.assertAlmostEqual(rms_db([16384, -16384] * 100), -6.02, places=2)

    def test_silence_and_empty_input_hit_the_floor(self):
        self.assertEqual(rms_db([0] * 100), -96.0)
        self.assertEqual(rms_db([]), -96.0)

    def test_sensitivity_maps_to_margin(self):
        self.assertAlmostEqual(margin_for_sensitivity(0), 30.0)
        self.assertAlmostEqual(margin_for_sensitivity(50), 19.0)
        self.assertAlmostEqual(margin_for_sensitivity(100), 8.0)
        self.assertAlmostEqual(margin_for_sensitivity(500), 8.0)


if __name__ == "__main__":
    unittest.main()
