import unittest
from datetime import date, datetime

from app.model import habit_repeat_days, habit_support, is_usable, wilson_lower_bound


class HabitRulesTest(unittest.TestCase):
    def test_repeat_days_from_weekend_ratio(self):
        self.assertEqual(habit_repeat_days(0.0, 10), [1, 2, 3, 4, 5])
        self.assertEqual(habit_repeat_days(1.0, 10), [6, 7])
        self.assertEqual(habit_repeat_days(0.3, 10), [])
        self.assertEqual(habit_repeat_days(0.0, 3), [])  # too few samples to restrict days

    def test_support_counts_only_matching_day_type(self):
        # 2026-11-02..06 are Mon..Fri, 07/08 the weekend
        active = {date(2026, 11, d) for d in range(2, 9)}
        rows = [{'occurred_at': datetime(2026, 11, d, 7, 0)} for d in (2, 3, 4)]
        self.assertAlmostEqual(habit_support(rows, active, [1, 2, 3, 4, 5]), 3 / 5)
        self.assertAlmostEqual(habit_support(rows, active, []), 3 / 7)

    def test_wilson_bound_needs_more_days_for_the_same_rate(self):
        self.assertLess(wilson_lower_bound(3, 3), 0.8)        # 3/3 days: not enough to be sure
        self.assertGreater(wilson_lower_bound(15, 15), 0.9)   # 3 weeks of weekdays
        self.assertLess(wilson_lower_bound(9, 10), wilson_lower_bound(18, 20))
        self.assertEqual(wilson_lower_bound(0, 0), 0.0)

    def test_model_must_beat_day_counting(self):
        self.assertTrue(is_usable(0.08, 0.10))   # lower Brier = better probabilities
        self.assertTrue(is_usable(0.10, 0.10))
        self.assertFalse(is_usable(0.12, 0.10))


class HabitOccurrenceTest(unittest.TestCase):
    def test_occurrence_window_and_midnight(self):
        import pandas as pd
        from zoneinfo import ZoneInfo
        from app.pipeline import habit_occurrences
        vn = ZoneInfo('Asia/Ho_Chi_Minh')
        days = [date(2026, 11, 2), date(2026, 11, 3), date(2026, 11, 4)]
        times = pd.Series([
            pd.Timestamp(datetime(2026, 11, 2, 23, 40, tzinfo=vn)),  # 10 min early
            pd.Timestamp(datetime(2026, 11, 4, 0, 10, tzinfo=vn)),   # 20 min late, belongs to Nov 3
            pd.Timestamp(datetime(2026, 11, 4, 22, 0, tzinfo=vn)),   # 1h50 early: not the habit
        ])
        hit, offset = habit_occurrences(times, days, 23 * 60 + 50)
        self.assertEqual(list(hit), [True, True, False])
        self.assertEqual(list(offset[:2]), [-10, 20])

    def test_features_only_use_prior_days(self):
        import numpy as np
        from app.pipeline import habit_features
        hit = np.array([True, True, False, True, True])
        offset = np.array([0, 10, np.nan, -10, 0])
        f = habit_features(hit, offset, [0, 1, 2, 3], weekend=False, action='TURN_ON')  # target is day 4
        self.assertEqual(f['history_days'], 4)
        self.assertAlmostEqual(f['rate'], 0.75)
        self.assertAlmostEqual(f['rate_recent'], 2 / 3)
        self.assertEqual(f['streak'], 1)
        self.assertEqual(f['days_since'], 1)

    def test_mean_time_rounds_the_whole_time(self):
        from app.pipeline import circular_mean_time
        hour, minute, _ = circular_mean_time([719.0, 720.0, 720.0, 720.0, 719.0])  # 11:59.6
        self.assertEqual((hour, minute), (12, 0))


if __name__ == '__main__':
    unittest.main()
