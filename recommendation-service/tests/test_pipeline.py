import unittest
from datetime import datetime, timezone, timedelta
from zoneinfo import ZoneInfo
import pandas as pd
import numpy as np

from app.pipeline import (
    FEATURE_COLUMNS,
    ColdStartException,
    build_habit_dataset,
    circular_minute_difference,
    circular_mean_time,
    cluster_timestamps_by_habit
)

class TestPipeline(unittest.TestCase):

    def test_circular_minute_difference(self):
        # Normal day difference
        self.assertEqual(circular_minute_difference(100, 130), 30)
        # Wrap around midnight: 23:55 (1435) to 00:15 (15) is 20 minutes!
        self.assertEqual(circular_minute_difference(1435, 15), 20)
        self.assertEqual(circular_minute_difference(15, 1435), 20)
        # Opposite sides of day: 12:00 (720) and 00:00 (0) is 720 minutes
        self.assertEqual(circular_minute_difference(720, 0), 720)

    def test_circular_mean_time_around_midnight(self):
        # 23:30 (1410 min) and 00:30 (30 min)
        # Linear mean would erroneously be (1410 + 30)/2 = 720 (12:00 PM NOON!)
        # Circular mean must correctly be 00:00 (Midnight!)
        avg_h, avg_m, std = circular_mean_time([1410.0, 30.0])
        self.assertEqual(avg_h, 0)
        self.assertEqual(avg_m, 0)
        self.assertLessEqual(std, 45.0)

    def test_bimodal_habit_clustering(self):
        # Device has 2 distinct routines:
        # Routine 1: 12:00 trưa (around 12:00, 12:05, 11:55)
        # Routine 2: 22:00 tối (around 22:00, 22:10, 21:50)
        rows = [
            {'occurred_at': datetime(2026, 10, 1, 11, 55, tzinfo=timezone.utc), 'current_state': {'power': 'ON'}},
            {'occurred_at': datetime(2026, 10, 2, 12, 0, tzinfo=timezone.utc), 'current_state': {'power': 'ON'}},
            {'occurred_at': datetime(2026, 10, 3, 12, 5, tzinfo=timezone.utc), 'current_state': {'power': 'ON'}},
            {'occurred_at': datetime(2026, 10, 1, 21, 50, tzinfo=timezone.utc), 'current_state': {'power': 'ON', 'temperature': 24}},
            {'occurred_at': datetime(2026, 10, 2, 22, 0, tzinfo=timezone.utc), 'current_state': {'power': 'ON', 'temperature': 24}},
            {'occurred_at': datetime(2026, 10, 3, 22, 10, tzinfo=timezone.utc), 'current_state': {'power': 'ON', 'temperature': 25}},
        ]
        df = pd.DataFrame(rows)
        clusters = cluster_timestamps_by_habit(df, max_gap_minutes=75.0, min_cluster_size=3)

        # Must find exactly 2 distinct clusters, NOT one merged cluster at 17:00!
        self.assertEqual(len(clusters), 2)
        hours = sorted([c['avg_hour'] for c in clusters])
        self.assertEqual(hours[0], 12)
        self.assertEqual(hours[1], 22)
        
        # Check learned parameters for the evening cluster
        night_cluster = next(c for c in clusters if c['avg_hour'] == 22)
        self.assertEqual(night_cluster['learned_parameters'].get('temperature'), 24)

    def test_cold_start_when_too_few_actions(self):
        df = synthetic_events(days=3)
        with self.assertRaises(ColdStartException):
            build_habit_dataset(df, min_samples=20, min_days=7)

    def test_habit_rows_are_labelled_from_the_day_itself_only(self):
        # The 18:30 weekday habit is skipped on the last Friday: that row is a miss, and its features
        # (built from earlier days) still show a strong habit -> no leakage of the day's own outcome.
        df = synthetic_events(days=26)
        last = df['occurred_at'].max().date()
        df = df[~((df['device_id'] == 'L1') & (df['action'] == 'TURN_ON') & (df['occurred_at'].dt.date == last))]
        dataset = build_habit_dataset(df, min_samples=20, min_days=7)
        self.assertEqual(list(dataset.columns[:len(FEATURE_COLUMNS)]), FEATURE_COLUMNS)
        on_rows = dataset[(dataset['is_turn_on'] == 1) & (dataset['device_id'] == 'L1')]
        missed = on_rows[on_rows['day'] == last]
        self.assertEqual(len(missed), 1)
        self.assertEqual(missed.iloc[0]['label'], 0)
        self.assertGreater(missed.iloc[0]['rate'], 0.9)

    def test_automated_actions_are_not_habits(self):
        df = synthetic_events(days=21)
        df['event_source'] = 'SCENE'
        with self.assertRaises(ColdStartException):
            build_habit_dataset(df, min_samples=20, min_days=7)

    def test_simulated_and_unknown_sources_are_not_habits(self):
        for source in ('SIMULATED', 'SENSOR', 'UNKNOWN'):
            df = synthetic_events(days=21)
            df['event_source'] = source
            with self.assertRaises(ColdStartException):
                build_habit_dataset(df, min_samples=20, min_days=7)


VN = ZoneInfo('Asia/Ho_Chi_Minh')


def event(device, action, at, source='MANUAL', device_type='LIGHT'):
    return {'event_type': 'DEVICE_ACTION', 'action': action, 'device_id': device, 'event_source': source,
            'occurred_at': pd.Timestamp(at), 'device_type': device_type, 'device_name': f'Thiết bị {device}',
            'room_id': None, 'room_name': 'Phòng khách', 'current_state': {'power': 'ON'}}


def synthetic_events(days=28, seed=7):
    """Light on ~18:30 every weekday and off ~23:00 every day; a fan used at random times."""
    rng = np.random.default_rng(seed)
    start = datetime(2026, 10, 5, tzinfo=VN)  # Monday
    rows = []
    for d in range(days):
        day = start + timedelta(days=d)
        if day.weekday() < 5:
            rows.append(event('L1', 'TURN_ON', day + timedelta(hours=18, minutes=int(rng.integers(20, 40)))))
        rows.append(event('L1', 'TURN_OFF', day + timedelta(hours=23, minutes=int(rng.integers(0, 15)))))
        if rng.random() < 0.5:
            rows.append(event('F1', 'TURN_ON', day + timedelta(minutes=int(rng.integers(0, 1440))), device_type='FAN'))
    return pd.DataFrame(rows)


def changing_events(days=56, seed=7):
    """synthetic_events plus habits that change: L2 ~07:00 stops 2 weeks before the end, L3 ~21:00 starts 3 weeks before."""
    rng = np.random.default_rng(seed + 1)
    start = datetime(2026, 10, 5, tzinfo=VN)
    rows = []
    for d in range(days):
        day = start + timedelta(days=d)
        if d < days - 14 and rng.random() < 0.9:
            rows.append(event('L2', 'TURN_ON', day + timedelta(hours=7, minutes=int(rng.integers(-10, 11)))))
        if d >= days - 21 and rng.random() < 0.95:
            rows.append(event('L3', 'TURN_ON', day + timedelta(hours=21, minutes=int(rng.integers(-10, 11)))))
    return pd.concat([synthetic_events(days=days, seed=seed), pd.DataFrame(rows)], ignore_index=True)


if __name__ == '__main__':
    unittest.main()
