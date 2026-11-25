"""Maps YAMNet's 521 AudioSet classes onto the handful of sound types Pet Check stores.

Mirrors app/src/main/java/.../audio/SoundTypeMapper.kt.
"""

from __future__ import annotations

SOUND_TYPES = ("bark", "howl", "whine", "growl", "meow", "other")
PET_TYPES = ("bark", "howl", "whine", "growl", "meow")

# YAMNet label -> stored type. Matched by label text, not index.
LABEL_TYPES = {
    "Bark": "bark",
    "Yip": "bark",
    "Bow-wow": "bark",
    "Howl": "howl",
    "Whimper (dog)": "whine",
    "Growling": "growl",
    "Meow": "meow",
    "Caterwaul": "meow",
}
# Generic labels: used only when no specific label is confident enough.
GENERIC_LABELS = {"Dog": "bark", "Cat": "meow"}

MIN_CONFIDENCE = 0.35


class SoundTypeMapper:
    def __init__(self, labels: list[str]):
        self.specific = [(i, LABEL_TYPES[name]) for i, name in enumerate(labels) if name in LABEL_TYPES]
        self.generic = [(i, GENERIC_LABELS[name]) for i, name in enumerate(labels) if name in GENERIC_LABELS]
        missing = (set(LABEL_TYPES) | set(GENERIC_LABELS)) - set(labels)
        if missing:
            raise ValueError(f"label list is missing: {sorted(missing)}")

    def accumulator(self) -> "TypeAccumulator":
        return TypeAccumulator(self)


class TypeAccumulator:
    """Collects classifier scores over the windows of one event and picks its type."""

    def __init__(self, mapper: SoundTypeMapper):
        self._mapper = mapper
        self._specific = dict.fromkeys(PET_TYPES, 0.0)
        self._generic = dict.fromkeys(PET_TYPES, 0.0)
        self.windows = 0

    def add(self, scores) -> None:
        self.windows += 1
        for index, kind in self._mapper.specific:
            self._specific[kind] = max(self._specific[kind], float(scores[index]))
        for index, kind in self._mapper.generic:
            self._generic[kind] = max(self._generic[kind], float(scores[index]))

    def result(self, min_confidence: float = MIN_CONFIDENCE) -> tuple[str, float]:
        """Returns (type, confidence). Ties resolve in PET_TYPES order."""
        for table in (self._specific, self._generic):
            best = max(PET_TYPES, key=lambda kind: table[kind])
            if table[best] >= min_confidence:
                return best, table[best]
        return "other", 0.0


def load_labels(path: str) -> list[str]:
    with open(path, encoding="utf-8") as handle:
        return [line.rstrip("\n") for line in handle if line.strip()]


class YamnetClassifier:
    """Runs the YAMNet TFLite model: 15600 samples at 16 kHz in, 521 scores out."""

    WINDOW_SAMPLES = 15600

    def __init__(self, model_path: str):
        try:
            from ai_edge_litert.interpreter import Interpreter
        except ImportError:  # older Raspberry Pi OS images
            from tflite_runtime.interpreter import Interpreter
        import numpy

        self._np = numpy
        self._interpreter = Interpreter(model_path=model_path, num_threads=2)
        self._interpreter.allocate_tensors()
        self._input = self._interpreter.get_input_details()[0]["index"]
        self._output = self._interpreter.get_output_details()[0]["index"]

    def classify(self, window):
        data = self._np.asarray(window, dtype=self._np.float32).reshape(self.WINDOW_SAMPLES)
        self._interpreter.set_tensor(self._input, data)
        self._interpreter.invoke()
        return self._interpreter.get_tensor(self._output).reshape(-1)
