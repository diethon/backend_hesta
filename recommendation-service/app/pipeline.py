import math
import re
from datetime import datetime, timedelta
from typing import Dict, Any, List, Optional, Tuple
import numpy as np
import pandas as pd
import psycopg2
from app.config import settings

# Sources where a person acted (PHYSICAL = wall switch). An allowlist: a new system source (SIMULATED, SENSOR,
# UNKNOWN...) must never be learned as a human habit by accident.
HUMAN_SOURCES = {'MANUAL', 'VOICE', 'GESTURE', 'PHYSICAL'} | ({'SIMULATED'} if settings.INCLUDE_SIMULATED else set())

class ColdStartException(Exception):
    """Raised when there is insufficient historical behavior data."""
    pass

def circular_minute_difference(m1: float, m2: float) -> float:
    """
    Calculates the shortest angular distance in minutes on a 24-hour circular dial (0..1440).
    Correctly accounts for wrap-around across midnight (e.g. 23:55 and 00:15 is 20 minutes apart).
    """
    diff = abs(m1 - m2) % 1440.0
    return min(diff, 1440.0 - diff)

def circular_mean_time(minutes_list: List[float]) -> Tuple[int, int, float]:
    """
    Calculates the circular mean time (hour, minute) and circular dispersion (standard deviation in minutes)
    using circular statistics (directional statistics).
    """
    if not minutes_list:
        return 0, 0, 0.0

    angles = [2.0 * math.pi * (m / 1440.0) for m in minutes_list]
    sin_sum = sum(math.sin(a) for a in angles)
    cos_sum = sum(math.cos(a) for a in angles)
    n = len(minutes_list)

    mean_angle = math.atan2(sin_sum, cos_sum)
    if mean_angle < 0:
        mean_angle += 2.0 * math.pi

    mean_minute_float = (mean_angle / (2.0 * math.pi)) * 1440.0
    # Round the whole time first: 11:59.6 must become 12:00, not hour 11 + minute 60%60 = 11:00.
    total_minutes = int(round(mean_minute_float)) % 1440
    avg_hour, avg_minute = divmod(total_minutes, 60)

    r_bar = math.sqrt((cos_sum / n) ** 2 + (sin_sum / n) ** 2)
    r_bar = max(min(r_bar, 1.0), 1e-6)
    circ_std_minutes = math.sqrt(-2.0 * math.log(r_bar)) * (1440.0 / (2.0 * math.pi))

    return avg_hour, avg_minute, circ_std_minutes

def cluster_timestamps_by_habit(
    events: pd.DataFrame,
    max_gap_minutes: float = 75.0,
    min_cluster_size: int = 3,
    max_std_minutes: float = 60.0
) -> List[Dict[str, Any]]:
    """
    Clusters a device's historical action events into distinct, temporally stable habit clusters.
    Solves the bimodal habit problem (e.g. morning vs evening routines) and midnight wrap-around.
    """
    if events.empty or len(events) < min_cluster_size:
        return []

    # Extract minute of day [0..1440) for each event
    items: List[Dict[str, Any]] = []
    for _, row in events.iterrows():
        t = row['occurred_at']
        m = t.hour * 60 + t.minute
        items.append({
            'minute_of_day': m,
            'row': row
        })

    # Sort sequentially along the day
    items.sort(key=lambda x: x['minute_of_day'])

    # 1. Linear pass to partition points into groups separated by > max_gap_minutes
    raw_clusters: List[List[Dict[str, Any]]] = []
    current_cluster: List[Dict[str, Any]] = [items[0]]

    for i in range(1, len(items)):
        prev_m = items[i - 1]['minute_of_day']
        curr_m = items[i]['minute_of_day']
        if (curr_m - prev_m) <= max_gap_minutes:
            current_cluster.append(items[i])
        else:
            raw_clusters.append(current_cluster)
            current_cluster = [items[i]]
    if current_cluster:
        raw_clusters.append(current_cluster)

    # 2. Check circular wrap-around across midnight between last cluster and first cluster
    if len(raw_clusters) > 1:
        first_min = raw_clusters[0][0]['minute_of_day']
        last_min = raw_clusters[-1][-1]['minute_of_day']
        if circular_minute_difference(last_min, first_min) <= max_gap_minutes:
            # Merge last cluster into first cluster
            merged = raw_clusters[-1] + raw_clusters[0]
            raw_clusters = [merged] + raw_clusters[1:-1]

    # 3. Evaluate each cluster against quality and stability thresholds
    stable_clusters: List[Dict[str, Any]] = []
    for cluster in raw_clusters:
        if len(cluster) < min_cluster_size:
            continue

        cluster_minutes = [c['minute_of_day'] for c in cluster]
        avg_hour, avg_minute, circ_std = circular_mean_time(cluster_minutes)

        # Reject dispersed/randomly scattered clusters that lack stability
        if circ_std > max_std_minutes:
            continue

        cluster_rows = [c['row'] for c in cluster]
        dows = [r['occurred_at'].weekday() for r in cluster_rows]
        is_weekend_ratio = sum(1 for d in dows if d >= 5) / len(dows)

        # Extract learned parameters from device current_state (e.g. AC temperature/mode, dimmer brightness)
        learned_params: Dict[str, Any] = {}
        states = [r.get('current_state') for r in cluster_rows if isinstance(r.get('current_state'), dict)]
        if states:
            # Check AC temperature
            temps = [s.get('temperature') or s.get('temp') for s in states if s.get('temperature') or s.get('temp')]
            valid_temps = []
            for tp in temps:
                try:
                    valid_temps.append(int(float(tp)))
                except (ValueError, TypeError):
                    pass
            if valid_temps:
                learned_params['temperature'] = int(round(float(np.median(valid_temps))))

            # Check AC mode
            modes = [str(s.get('mode')).upper() for s in states if s.get('mode')]
            if modes:
                learned_params['mode'] = max(set(modes), key=modes.count)

            # Check Dimmer brightness / level
            brightness_vals = [s.get('brightness') or s.get('level') for s in states if s.get('brightness') or s.get('level')]
            valid_br = []
            for b in brightness_vals:
                try:
                    valid_br.append(int(float(b)))
                except (ValueError, TypeError):
                    pass
            if valid_br:
                learned_params['brightness'] = int(round(float(np.median(valid_br))))

        stable_clusters.append({
            'avg_hour': avg_hour,
            'avg_minute': avg_minute,
            'circ_std_minutes': round(circ_std, 2),
            'observation_count': len(cluster),
            'is_weekend_ratio': is_weekend_ratio,
            'cluster_rows': cluster_rows,
            'learned_parameters': learned_params
        })

    return stable_clusters

def load_events_from_db(
    db_url: Optional[str] = None,
    home_id: Optional[str] = None,
    from_time: Optional[datetime] = None,
    to_time: Optional[datetime] = None,
    event_type: Optional[str] = 'DEVICE_ACTION',
) -> pd.DataFrame:
    """Load behavior events (device actions by default) with device and room names, in local time."""
    url = db_url or settings.DATABASE_URL
    if not url:
        raise RuntimeError("DATABASE_URL chưa được cấu hình (đặt biến môi trường hoặc file .env).")
    conn = psycopg2.connect(url, connect_timeout=10)
    try:
        where_clause = "WHERE e.occurred_at IS NOT NULL"
        params: List[Any] = []
        if home_id:
            where_clause += " AND e.home_id = %s"
            params.append(home_id)
        # Filter in SQL: behavior_events also holds sensor reports, loading full history gets slower forever.
        if from_time:
            where_clause += " AND e.occurred_at >= %s"
            params.append(from_time)
        if to_time:
            where_clause += " AND e.occurred_at <= %s"
            params.append(to_time)
        if event_type:
            where_clause += " AND e.event_type = %s"
            params.append(event_type)

        query = f"""
            SELECT e.id, e.home_id, e.user_id, e.room_id, e.device_id,
                   d.device_type, d.name AS device_name, r.name AS room_name,
                   e.event_type, e.action, e.event_source, e.previous_state,
                   e.current_state, e.occurred_at
            FROM behavior_events e
            LEFT JOIN devices d ON e.device_id = d.id
            LEFT JOIN rooms r ON e.room_id = r.id
            {where_clause}
            ORDER BY e.occurred_at ASC;
        """
        cur = conn.cursor()
        cur.execute(query, tuple(params) if params else None)
        columns = [desc[0] for desc in cur.description]
        df = pd.DataFrame(cur.fetchall(), columns=columns)
        if not df.empty and 'occurred_at' in df.columns:
            # Habits are local-time concepts: .hour must be Vietnam time, not UTC (was 7 h off).
            df['occurred_at'] = pd.to_datetime(df['occurred_at'], utc=True).dt.tz_convert(settings.TIMEZONE)
        return df
    finally:
        conn.close()


def list_homes_with_actions(db_url: Optional[str] = None) -> List[str]:
    """Homes that have at least one recorded device action (the ones worth training)."""
    url = db_url or settings.DATABASE_URL
    if not url:
        raise RuntimeError("DATABASE_URL chưa được cấu hình.")
    conn = psycopg2.connect(url, connect_timeout=10)
    try:
        cur = conn.cursor()
        cur.execute("SELECT DISTINCT home_id FROM behavior_events WHERE event_type = 'DEVICE_ACTION' AND home_id IS NOT NULL")
        return [str(row[0]) for row in cur.fetchall()]
    finally:
        conn.close()


# ─────────────────────────────────────────────────────────────────────────────
# Habit dataset: one row per (habit, day). A habit is a cluster of the same action on the same device at
# about the same time (see cluster_timestamps_by_habit). The question the model answers:
# "will this habit happen (within its time window) on its next day?"
# Unlike one row per 30-minute slot, the classes are balanced and a habit drifting across a slot border
# does not split its signal. Features only look at earlier days, and the same function builds training
# rows and inference rows, so training and serving can never drift apart.
# ─────────────────────────────────────────────────────────────────────────────

HABIT_WINDOW_MINUTES = 30  # an action this close to the habit time counts as the habit happening...
MAX_WINDOW_MINUTES = 60    # ...widened for habits that naturally wander more (see habit_window)
HISTORY_DAYS = 14          # previous relevant days the features look at
MIN_HISTORY_DAYS = 3       # a day needs at least this much history to become a training row
ACTION_LABELS = ['TURN_ON', 'TURN_OFF']

# Only how the habit behaved, not which habit it is: time of day and device type let the forest memorise
# individual habits and it lost to plain day counting (Brier, virtual-home test over 10 seeds).
FEATURE_COLUMNS = [
    'history_days', 'rate', 'rate_recent', 'streak', 'days_since', 'spread_minutes', 'is_turn_on', 'is_weekend',
]


def user_actions(df: pd.DataFrame) -> pd.DataFrame:
    """Power actions a person made (app, web, voice, wall switch): the ground truth for habits."""
    if df.empty:
        return df
    return df[
        (df['event_type'] == 'DEVICE_ACTION')
        & (df['action'].isin(ACTION_LABELS))
        & (df['device_id'].notna())
        & (df['event_source'].astype(str).str.upper().isin(HUMAN_SOURCES))
    ].copy()


def day_range(actions: pd.DataFrame) -> List[Any]:
    first = actions['occurred_at'].min().date()
    last = actions['occurred_at'].max().date()
    return [first + timedelta(days=i) for i in range((last - first).days + 1)]


def habit_repeat_days(weekend_ratio: float, observations: int) -> List[int]:
    """Weekday-only or weekend-only habits get matching ISO days; mixed habits run every day ([])."""
    if observations < 5:
        return []  # too few samples to tell weekday from weekend
    if weekend_ratio >= 0.9:
        return [6, 7]
    if weekend_ratio <= 0.1:
        return [1, 2, 3, 4, 5]
    return []


def habit_window(cluster: Dict[str, Any]) -> int:
    """±2 standard deviations of the habit's own times, 30..60 minutes. A fixed ±30 counted ~13% of the days
    of a habit with a 20-minute spread as "did not happen", although the clustering saw them as the habit."""
    return int(min(MAX_WINDOW_MINUTES, max(HABIT_WINDOW_MINUTES, round(2 * cluster['circ_std_minutes']))))


def habit_candidates(actions: pd.DataFrame) -> List[Dict[str, Any]]:
    """Every stable time-of-day habit per (device, action), as found by the clustering."""
    found = []
    for (device_id, action), group in actions.groupby(['device_id', 'action']):
        for cluster in cluster_timestamps_by_habit(group):
            found.append({
                'device_id': device_id, 'action': action, 'cluster': cluster,
                'minute': cluster['avg_hour'] * 60 + cluster['avg_minute'],
                'window': habit_window(cluster),
                'repeat_days': habit_repeat_days(cluster['is_weekend_ratio'], cluster['observation_count']),
            })
    return found


def habit_occurrences(times: pd.Series, days: List[Any], minute: int,
                      window: int = HABIT_WINDOW_MINUTES) -> Tuple[np.ndarray, np.ndarray]:
    """Per day: did the action happen within the habit window, and how many minutes off (signed)."""
    index = {day: i for i, day in enumerate(days)}
    hit = np.zeros(len(days), dtype=bool)
    offset = np.full(len(days), np.nan)
    for ts in times:
        diff = (ts.hour * 60 + ts.minute - minute + 720) % 1440 - 720  # signed, wraps around midnight
        if abs(diff) > window:
            continue
        # 00:10 for a 23:50 habit belongs to the previous day's occurrence.
        i = index.get((ts - timedelta(minutes=int(diff))).date())
        if i is not None and (not hit[i] or abs(diff) < abs(offset[i])):
            hit[i], offset[i] = True, diff
    return hit, offset


def habit_relevant_days(days: List[Any], active_dates: set, repeat_days: List[int]) -> List[int]:
    """Indexes of days the habit could have happened: its day type, and the user did something by hand."""
    return [i for i, day in enumerate(days)
            if day in active_dates and (not repeat_days or day.isoweekday() in repeat_days)]


def habit_features(hit: np.ndarray, offset: np.ndarray, prior: List[int],
                   weekend: bool, action: str) -> Dict[str, float]:
    """Features of one habit for a target day, from its earlier relevant days only (`prior`, oldest first)."""
    past = hit[prior] if prior else np.zeros(0, dtype=bool)
    hits_at = [n for n, h in enumerate(past) if h]
    streak = 0
    for h in past[::-1]:
        if not h:
            break
        streak += 1
    offsets = offset[prior][past] if prior else np.zeros(0)
    return {
        'history_days': len(prior),
        'rate': float(past.mean()) if len(past) else 0.0,
        'rate_recent': float(past[-3:].mean()) if len(past) else 0.0,
        'streak': streak,
        'days_since': len(past) - hits_at[-1] if hits_at else HISTORY_DAYS + 1,
        'spread_minutes': float(np.std(offsets)) if len(offsets) >= 2 else float(HABIT_WINDOW_MINUTES),
        'is_turn_on': 1 if action == 'TURN_ON' else 0,
        'is_weekend': 1 if weekend else 0,
    }


def _habit_times(actions: pd.DataFrame, habit: Dict[str, Any]) -> pd.Series:
    mask = (actions['device_id'] == habit['device_id']) & (actions['action'] == habit['action'])
    return actions.loc[mask, 'occurred_at']


def build_habit_dataset(df: pd.DataFrame, min_samples: int = 20, min_days: int = 7,
                        habits_before: Optional[Any] = None) -> pd.DataFrame:
    """
    Training rows for every habit and every relevant day that has enough history.
    `shown` marks the rows the recommender would actually score (MIN_HABIT_DAYS of history, past rate at
    least MIN_HABIT_SUPPORT). Training uses every row (young and fading habits teach the model about change),
    but the model is judged on the shown rows only: most other rows are noise "habits" that are easy to call,
    and a model could win on them while underrating the real habits users see.
    `habits_before`: find the habits only in days before this date, so test days cannot leak into them.
    """
    actions = user_actions(df)
    if len(actions) < min_samples:
        raise ColdStartException(
            f"Chưa đủ thao tác thật của người dùng ({len(actions)}/{min_samples} lần bật/tắt).")
    days = day_range(actions)
    if len(days) < min_days:
        raise ColdStartException(f"Dữ liệu mới trải dài {len(days)}/{min_days} ngày, chưa đủ để học thói quen.")

    source = actions if habits_before is None else actions[actions['occurred_at'].dt.date < habits_before]
    active_dates = set(actions['occurred_at'].dt.date)
    rows: List[Dict[str, Any]] = []
    for habit in habit_candidates(source):
        hit, offset = habit_occurrences(_habit_times(actions, habit), days, habit['minute'], habit['window'])
        relevant = habit_relevant_days(days, active_dates, habit['repeat_days'])
        for position, i in enumerate(relevant):
            prior = relevant[max(0, position - HISTORY_DAYS):position]
            if len(prior) < MIN_HISTORY_DAYS:
                continue
            features = habit_features(hit, offset, prior, days[i].weekday() >= 5, habit['action'])
            shown = len(prior) >= settings.MIN_HABIT_DAYS and features['rate'] >= settings.MIN_HABIT_SUPPORT
            rows.append({**features, 'day': days[i], 'device_id': str(habit['device_id']),
                         'shown': shown, 'label': int(hit[i])})

    if not rows:
        raise ColdStartException("Chưa có thói quen nào đủ lịch sử để tạo dữ liệu huấn luyện.")
    return pd.DataFrame(rows).sort_values(['day', 'device_id']).reset_index(drop=True)


def next_day_habit_features(actions: pd.DataFrame, days: List[Any], active_dates: set,
                            habit: Dict[str, Any]) -> pd.DataFrame:
    """Inference row: the habit on its next relevant day after the data window."""
    hit, offset = habit_occurrences(_habit_times(actions, habit), days, habit['minute'], habit['window'])
    prior = habit_relevant_days(days, active_dates, habit['repeat_days'])[-HISTORY_DAYS:]
    weekend = habit['repeat_days'] == [6, 7]
    row = habit_features(hit, offset, prior, weekend, habit['action'])
    return pd.DataFrame([row], columns=FEATURE_COLUMNS)
