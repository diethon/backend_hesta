import shutil
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path

from app.model import BehaviorModelManager, prune_old_versions
from tests.test_pipeline import changing_events, synthetic_events

HOME = "home-test"


class TestModel(unittest.TestCase):

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.manager = BehaviorModelManager(artifacts_dir=self.dir)
        self.events = synthetic_events(days=28)
        # ML only beats day counting when habits change (start / stop); stable habits are what counting is for.
        self.changing = changing_events()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def test_cold_start_still_recommends_strong_habits(self):
        self.assertFalse(self.manager.is_ready(HOME))
        self.assertEqual(self.manager.get_status(HOME).status, "COLD_START")

        proposals, evaluated, eligible = self.manager.generate_recommendations_for_home(HOME, events=self.events)

        self.assertGreater(evaluated, 0)
        evening_on = [p for p in proposals if p.deviceId == "L1" and p.action == "TURN_ON"]
        self.assertEqual(len(evening_on), 1)
        self.assertTrue(evening_on[0].time.startswith("18:"))
        self.assertEqual(evening_on[0].repeatDays, [1, 2, 3, 4, 5])
        self.assertEqual(evening_on[0].engineType, "RULE_BASED")
        self.assertGreaterEqual(evening_on[0].confidence, 0.80)
        self.assertIn("/20 ngày thường", evening_on[0].reason)
        # A fan switched on at random times is not a habit.
        self.assertFalse(any(p.deviceId == "F1" for p in proposals))
        self.assertEqual(eligible, len(proposals))

    def test_prune_keeps_newest_candidates(self):
        home = self.dir / "h"
        home.mkdir()
        for v in ("20260101", "20260102", "20260103"):
            (home / f"model_rf-v{v}.joblib").touch()
        (home / "model_latest.joblib").touch()
        prune_old_versions(home, keep=2)
        self.assertEqual(sorted(f.name for f in home.iterdir()),
                         ["model_latest.joblib", "model_rf-v20260102.joblib", "model_rf-v20260103.joblib"])

    def test_training_is_per_home_and_persists(self):
        metadata = self.manager.train_home(HOME, self.changing)

        self.assertTrue(metadata["promoted"])
        self.assertLessEqual(metadata["brier_score"], metadata["baseline_brier"])
        self.assertTrue(self.manager.is_ready(HOME))
        self.assertFalse(self.manager.is_ready("other-home"))
        self.assertTrue((self.dir / HOME / "model_latest.joblib").exists())
        self.assertGreater(metadata["f1_score"], 0.8)

        reloaded = BehaviorModelManager(artifacts_dir=self.dir)
        status = reloaded.get_status(HOME)
        self.assertEqual(status.status, "READY")
        self.assertEqual(status.version, metadata["version"])

    def test_trained_model_scores_habits(self):
        self.manager.train_home(HOME, self.changing)
        recent = self.changing[self.changing['occurred_at'] >= self.changing['occurred_at'].max() - timedelta(days=30)]
        proposals, _, _ = self.manager.generate_recommendations_for_home(HOME, events=recent)

        evening_on = [p for p in proposals if p.deviceId == "L1" and p.action == "TURN_ON"]
        self.assertEqual(len(evening_on), 1)
        self.assertEqual(evening_on[0].engineType, "ML")
        self.assertGreaterEqual(evening_on[0].confidence, 0.80)
        self.assertFalse(any(p.deviceId == "F1" for p in proposals))
        self.assertFalse(any(p.deviceId == "L2" for p in proposals))   # habit stopped 2 weeks ago
        self.assertTrue(any(p.deviceId == "L3" for p in proposals))    # new habit, 3 weeks old

    def test_retraining_compares_on_the_same_test_days(self):
        self.manager.train_home(HOME, self.changing, version="v1")
        second = self.manager.train_home(HOME, self.changing, version="v2")
        self.assertIsNotNone(second["champion_brier_on_test"])
        self.assertTrue(second["promoted"])  # same data, same score: not worse


if __name__ == '__main__':
    unittest.main()
