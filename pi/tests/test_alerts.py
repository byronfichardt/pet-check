import unittest
from dataclasses import replace

from petcheck_pi.alerts import AlertEvaluator, AlertRule, AlertTrigger

RULE = AlertRule(enabled=True, noisy_seconds=60, window_minutes=10, cooldown_minutes=30)
MINUTE = 60_000


class AlertTests(unittest.TestCase):
    def test_fires_once_enough_noise_builds_up_inside_the_window(self):
        evaluator = AlertEvaluator()
        self.assertIsNone(evaluator.on_event(RULE, 0, 25_000, "bark"))
        self.assertIsNone(evaluator.on_event(RULE, 2 * MINUTE, 25_000, "howl"))
        self.assertEqual(evaluator.on_event(RULE, 4 * MINUTE, 15_000, "bark"),
                         AlertTrigger(4 * MINUTE + 15_000, 65, 10, "bark", 3))

    def test_noise_older_than_the_window_does_not_count(self):
        evaluator = AlertEvaluator()
        self.assertIsNone(evaluator.on_event(RULE, 0, 40_000, "bark"))
        self.assertIsNone(evaluator.on_event(RULE, 11 * MINUTE, 40_000, "bark"))
        self.assertIsNotNone(evaluator.on_event(RULE, 13 * MINUTE, 40_000, "bark"))

    def test_stays_quiet_during_the_cooldown(self):
        evaluator = AlertEvaluator()
        self.assertIsNotNone(evaluator.on_event(RULE, 0, 90_000, "bark"))
        self.assertIsNone(evaluator.on_event(RULE, 5 * MINUTE, 90_000, "bark"))
        self.assertIsNone(evaluator.on_event(RULE, 20 * MINUTE, 90_000, "bark"))
        self.assertIsNotNone(evaluator.on_event(RULE, 31 * MINUTE, 90_000, "bark"))

    def test_other_sounds_only_count_when_asked_to(self):
        self.assertIsNone(AlertEvaluator().on_event(RULE, 0, 90_000, "other"))
        trigger = AlertEvaluator().on_event(replace(RULE, include_other=True), 0, 90_000, "other")
        self.assertEqual(trigger.type, "other")

    def test_disabled_rule_never_fires_and_forgets_what_it_heard(self):
        evaluator = AlertEvaluator()
        self.assertIsNone(evaluator.on_event(replace(RULE, enabled=False), 0, 90_000, "bark"))
        self.assertIsNone(evaluator.on_event(RULE, 2 * MINUTE, 10_000, "bark"))


if __name__ == "__main__":
    unittest.main()
