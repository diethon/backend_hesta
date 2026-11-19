import json
import logging
import math
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Any, List, Optional, Tuple

import joblib
import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, brier_score_loss, precision_recall_fscore_support, confusion_matrix

from app.config import settings
from app.pipeline import (
    FEATURE_COLUMNS,
    ColdStartException,
    build_habit_dataset,
    cluster_timestamps_by_habit,
    day_range,
    habit_repeat_days,  # noqa: F401  (re-exported: callers import it from here)
    habit_window,
    load_events_from_db,
    next_day_habit_features,
    user_actions,
)
from app.schemas import ModelStatusResponse, RecommendationProposal

logger = logging.getLogger(__name__)

HABIT_ENGINE_VERSION = "habit-v2"
MIN_TRAIN_ROWS = 60  # (habit, day) rows needed before a model can be trusted over day counting
MIN_TEST_ROWS = 20


def habit_days(cluster_rows: List[Any], active_dates: set, repeat_days: List[int]) -> Tuple[int, int]:
    """(days the habit happened, active days of the habit's day type)."""
    relevant = {d for d in active_dates if not repeat_days or d.isoweekday() in repeat_days}
    habit = {r['occurred_at'].date() for r in cluster_rows} & relevant
    return len(habit), len(relevant)


def habit_support(cluster_rows: List[Any], active_dates: set, repeat_days: List[int]) -> float:
    """Share of the user's active days (of the habit's day type) on which the habit happened."""
    k, n = habit_days(cluster_rows, active_dates, repeat_days)
    return k / max(1, n)


def wilson_lower_bound(successes: int, trials: int, z: float = 1.0) -> float:
    """Pessimistic estimate of a rate: 3/3 days is far less certain than 30/30 days."""
    if trials <= 0:
        return 0.0
    p = successes / trials
    denom = 1 + z * z / trials
    centre = p + z * z / (2 * trials)
    margin = z * math.sqrt(p * (1 - p) / trials + z * z / (4 * trials * trials))
    return max(0.0, (centre - margin) / denom)


def baseline_probability(X: pd.DataFrame) -> np.ndarray:
    """Without ML: the habit's past rate, smoothed (Laplace) so 2/2 days is not a certainty."""
    return ((X['rate'] * X['history_days'] + 1) / (X['history_days'] + 2)).to_numpy()


def binary_scores(y_true, probability) -> Dict[str, float]:
    """Brier score judges the probability itself (it is shown to users as confidence); F1 the yes/no call."""
    predicted = (np.asarray(probability) >= 0.5).astype(int)
    precision, recall, f1, _ = precision_recall_fscore_support(
        y_true, predicted, average='binary', pos_label=1, zero_division=0)
    return {
        'accuracy': float(accuracy_score(y_true, predicted)),
        'precision': float(precision),
        'recall': float(recall),
        'f1': float(f1),
        'brier': float(brier_score_loss(y_true, probability)),
    }


def is_usable(brier: float, baseline_brier: float) -> bool:
    """A model is only worth trusting when its probabilities are at least as good as plain day counting."""
    return brier <= baseline_brier


def prune_old_versions(home_dir: Path, keep: int) -> None:
    """Each training writes a candidate file; keep only the newest `keep` (names sort by timestamp)."""
    for pattern in ("model_rf-v*.joblib", "metadata_challenger_*.json"):
        files = sorted(home_dir.glob(pattern))
        for old in files[:max(0, len(files) - keep)]:
            old.unlink(missing_ok=True)


@dataclass
class HomeModel:
    model: Any
    classes: List[str]
    metadata: Dict[str, Any]

    def probabilities(self, features: pd.DataFrame) -> np.ndarray:
        """P(the habit happens) for each row."""
        if '1' not in self.classes:
            return np.zeros(len(features))
        return self.model.predict_proba(features)[:, self.classes.index('1')]

    def probability(self, features: pd.DataFrame) -> float:
        return float(self.probabilities(features)[0])


# One training at a time: the weekly job and a manual retrain would otherwise write the same model files.
_train_lock = threading.Lock()


class BehaviorModelManager:
    """One habit model per home: training with a by-day temporal split, persistence and recommendation."""

    def __init__(self, artifacts_dir: Optional[Path] = None):
        self.artifacts_dir = Path(artifacts_dir or settings.ARTIFACTS_DIR)
        self.artifacts_dir.mkdir(parents=True, exist_ok=True)
        self._models: Dict[str, Optional[HomeModel]] = {}

    def _home_dir(self, home_id: str) -> Path:
        return self.artifacts_dir / str(home_id)

    def get(self, home_id: Optional[str]) -> Optional[HomeModel]:
        if not home_id:
            return None
        if home_id not in self._models:
            self._models[home_id] = self._load(home_id)
        return self._models[home_id]

    def is_ready(self, home_id: Optional[str]) -> bool:
        return self.get(home_id) is not None

    def _load(self, home_id: str) -> Optional[HomeModel]:
        home_dir = self._home_dir(home_id)
        model_path, meta_path = home_dir / "model_latest.joblib", home_dir / "model_metadata.json"
        if not (model_path.exists() and meta_path.exists()):
            return None
        try:
            bundle = joblib.load(model_path)
            metadata = json.loads(meta_path.read_text(encoding='utf-8'))
            if bundle.get('feature_columns') != FEATURE_COLUMNS:
                logger.warning("Model of home %s was trained with other features; ignoring it.", home_id)
                return None
            if not is_usable(metadata.get('brier_score', 1.0), metadata.get('baseline_brier', 0.0)):
                logger.warning("Model of home %s does not beat the simple rule; ignoring it.", home_id)
                return None
            return HomeModel(bundle['model'], [str(c) for c in bundle['classes']], metadata)
        except Exception as e:
            logger.warning("Failed to load model of home %s: %s", home_id, e)
            return None

    # ------------------------------------------------------------------ training

    def train_home(self, home_id: str, df: pd.DataFrame, test_size: float = 0.20,
                   version: Optional[str] = None) -> Dict[str, Any]:
        with _train_lock:
            return self._train_home(home_id, df, test_size, version)

    def _train_home(self, home_id: str, df: pd.DataFrame, test_size: float,
                    version: Optional[str]) -> Dict[str, Any]:
        version = version or f"rf-v{datetime.now().strftime('%Y%m%d%H%M%S')}"
        actions = user_actions(df)
        # Rows exist only on days the user acted by hand, so split those, not calendar days (sparse data
        # otherwise left an almost empty test set).
        all_days = sorted(set(actions['occurred_at'].dt.date)) if not actions.empty else []

        # Split by whole days: the newest days are the test set, like predicting tomorrow.
        # Habits are found on the training days only, so the test days cannot leak into them.
        n_test = max(2, int(round(len(all_days) * test_size)))
        test_start = all_days[-n_test] if len(all_days) > n_test else None
        dataset = build_habit_dataset(df, settings.MIN_SAMPLES_REQUIRED, settings.MIN_TRAIN_DAYS,
                                      habits_before=test_start)
        if test_start is None:
            raise ColdStartException("Chưa đủ ngày để tách tập kiểm thử.")
        train_df = dataset[dataset['day'] < test_start]
        test_df = dataset[(dataset['day'] >= test_start) & dataset['shown']]  # judged where users see it
        if train_df['day'].nunique() < 3 or test_df.empty:
            raise ColdStartException(f"Mới có {len(all_days)} ngày có thao tác tay, cần thêm để tách tập kiểm thử.")
        if train_df['label'].nunique() < 2:
            raise ColdStartException("Tập huấn luyện chỉ có một loại kết quả (thói quen luôn/không bao giờ xảy ra).")
        # A Brier win on a handful of rows is luck (the real home was promoted on 12 train / 8 test rows).
        if len(train_df) < MIN_TRAIN_ROWS or len(test_df) < MIN_TEST_ROWS or train_df['label'].value_counts().min() < 5:
            raise ColdStartException(
                f"Chưa đủ dữ liệu thói quen để đánh giá mô hình ({len(train_df)} dòng huấn luyện, "
                f"{len(test_df)} dòng kiểm thử; cần {MIN_TRAIN_ROWS}/{MIN_TEST_ROWS}).")

        X_train, y_train = train_df[FEATURE_COLUMNS], train_df['label']
        X_test, y_test = test_df[FEATURE_COLUMNS], test_df['label']

        rf = RandomForestClassifier(n_estimators=200, max_depth=4, min_samples_leaf=10,
                                    random_state=settings.RANDOM_STATE, n_jobs=-1)
        counts = y_train.value_counts()
        calibrated = bool(counts.min() >= 3)
        # Probabilities are shown to users as confidence, so they have to mean what they say.
        model = CalibratedClassifierCV(estimator=rf, method='sigmoid', cv=3) if calibrated else rf
        model.fit(X_train, y_train)
        home_model = HomeModel(model, [str(c) for c in model.classes_], {})

        probability = home_model.probabilities(X_test)
        scores = binary_scores(y_test, probability)
        baseline = binary_scores(y_test, baseline_probability(X_test))

        # Champion-challenger on the SAME test rows, so the comparison is fair.
        champion = self.get(home_id)
        champion_scores = binary_scores(y_test, champion.probabilities(X_test)) if champion is not None else None
        usable = is_usable(scores['brier'], baseline['brier'])
        promoted = usable and (champion_scores is None or scores['brier'] <= champion_scores['brier'] + 0.005)
        if not usable:
            reason = (f"Brier {scores['brier']:.3f} chưa tốt hơn đếm tần suất ({baseline['brier']:.3f}) trên các ngày "
                      f"kiểm thử: chưa dùng mô hình, tiếp tục gợi ý theo tần suất.")
        elif champion_scores is None:
            reason = f"Mô hình đầu tiên của nhà (Brier {scores['brier']:.3f} ≤ tần suất {baseline['brier']:.3f})."
        elif promoted:
            reason = f"Brier mới {scores['brier']:.3f} ≤ mô hình cũ {champion_scores['brier']:.3f} trên cùng các ngày kiểm thử."
        else:
            reason = f"Brier mới {scores['brier']:.3f} kém mô hình cũ {champion_scores['brier']:.3f}: giữ mô hình cũ."

        metadata = {
            "version": version,
            "home_id": str(home_id),
            "trained_at": datetime.now(timezone.utc).isoformat(),
            "model_type": "RandomForestClassifier",
            "task": "habit_occurrence",
            "train_samples": int(len(train_df)),
            "test_samples": int(len(test_df)),
            "train_days": len(all_days) - n_test,
            "test_days": n_test,
            "user_actions": int(len(actions)),
            "devices": int(dataset['device_id'].nunique()),
            "classes": [str(c) for c in model.classes_],
            "label_distribution": {"HAPPENED": int(dataset['label'].sum()),
                                   "MISSED": int((dataset['label'] == 0).sum())},
            "accuracy": round(scores['accuracy'], 4),
            "precision": round(scores['precision'], 4),
            "recall": round(scores['recall'], 4),
            "f1_score": round(scores['f1'], 4),
            "brier_score": round(scores['brier'], 4),
            "baseline_accuracy": round(baseline['accuracy'], 4),
            "baseline_f1": round(baseline['f1'], 4),
            "baseline_brier": round(baseline['brier'], 4),
            "beats_baseline": usable,
            "champion_f1_on_test": None if champion_scores is None else round(champion_scores['f1'], 4),
            "champion_brier_on_test": None if champion_scores is None else round(champion_scores['brier'], 4),
            "calibrated": calibrated,
            "confidence_threshold": settings.CONFIDENCE_THRESHOLD,
            "confusion_matrix": confusion_matrix(y_test, (probability >= 0.5).astype(int), labels=[0, 1]).tolist(),
            "feature_columns": FEATURE_COLUMNS,
            "promoted": promoted,
            "promotion_reason": reason,
            "champion_version": version if promoted else (champion.metadata.get("version") if champion else None),
            "status": "PROMOTED" if promoted else "RETAINED_CHAMPION",
        }

        home_dir = self._home_dir(home_id)
        home_dir.mkdir(parents=True, exist_ok=True)
        bundle = {'model': model, 'classes': model.classes_, 'feature_columns': FEATURE_COLUMNS}
        joblib.dump(bundle, home_dir / f"model_{version}.joblib")  # every candidate kept for audit
        if promoted:
            joblib.dump(bundle, home_dir / "model_latest.joblib")
            (home_dir / "model_metadata.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding='utf-8')
            self._models[home_id] = HomeModel(model, [str(c) for c in model.classes_], metadata)
            logger.info("[home %s] promoted %s: %s", home_id, version, reason)
        else:
            (home_dir / f"metadata_challenger_{version}.json").write_text(
                json.dumps(metadata, indent=2, ensure_ascii=False), encoding='utf-8')
            logger.warning("[home %s] kept champion: %s", home_id, reason)
        prune_old_versions(home_dir, settings.KEEP_MODEL_VERSIONS)
        return metadata

    # ------------------------------------------------------------------ status

    def get_status(self, home_id: Optional[str]) -> ModelStatusResponse:
        home_model = self.get(home_id)
        if home_model is None:
            return ModelStatusResponse(
                status="COLD_START",
                version="none",
                confidenceThreshold=settings.CONFIDENCE_THRESHOLD,
                metrics={"reason": "Nhà này chưa có mô hình. Gợi ý vẫn được tính theo tần suất thói quen."},
            )
        meta = home_model.metadata
        return ModelStatusResponse(
            status="READY",
            version=meta.get("version", "unknown"),
            trainedAt=meta.get("trained_at"),
            trainSamples=meta.get("train_samples", 0),
            testSamples=meta.get("test_samples", 0),
            accuracy=meta.get("accuracy", 0.0),
            precision=meta.get("precision", 0.0),
            recall=meta.get("recall", 0.0),
            f1Score=meta.get("f1_score", 0.0),
            baselineAccuracy=meta.get("baseline_accuracy", 0.0),
            baselineF1=meta.get("baseline_f1", 0.0),
            confidenceThreshold=meta.get("confidence_threshold", settings.CONFIDENCE_THRESHOLD),
            calibrated=meta.get("calibrated", False),
            labelDistribution=meta.get("label_distribution", {}),
            metrics=meta,
        )

    # ------------------------------------------------------------------ recommending

    def generate_recommendations_for_home(
        self,
        home_id: str,
        from_time: Optional[datetime] = None,
        to_time: Optional[datetime] = None,
        threshold: Optional[float] = None,
        events: Optional[pd.DataFrame] = None,
    ) -> Tuple[List[RecommendationProposal], int, int]:
        """
        Finds time-of-day habits per device and action (circular clustering), then scores each one:
        with the home's model when it exists, otherwise with the Wilson lower bound of how many
        active days the habit happened on.
        """
        conf_threshold = threshold if threshold is not None else settings.CONFIDENCE_THRESHOLD
        if events is None:
            try:
                events = load_events_from_db(home_id=home_id, from_time=from_time, to_time=to_time)
            except Exception as e:
                logger.warning("Could not load events for home %s: %s", home_id, e)
                return [], 0, 0

        actions = user_actions(events)
        if actions.empty:
            return [], 0, 0

        active_dates = set(actions['occurred_at'].dt.date)  # days the user did anything by hand
        days = day_range(actions)
        home_model = self.get(home_id)
        evaluated = 0
        proposals: List[RecommendationProposal] = []

        for (dev_id, act), group in actions.groupby(['device_id', 'action']):
            evaluated += 1
            if len(group) < 3:
                continue
            sample = group.iloc[-1]
            dev_name = sample.get('device_name') or 'Thiết bị'

            for cluster in cluster_timestamps_by_habit(group, max_gap_minutes=75.0, min_cluster_size=3,
                                                       max_std_minutes=60.0):
                repeat_days = habit_repeat_days(cluster['is_weekend_ratio'], cluster['observation_count'])
                k, n = habit_days(cluster['cluster_rows'], active_dates, repeat_days)
                if n < settings.MIN_HABIT_DAYS or k / n < settings.MIN_HABIT_SUPPORT:
                    continue  # too little history, or it happened a few times but not on most days

                hour, minute = cluster['avg_hour'], cluster['avg_minute']
                if home_model is not None:
                    # Same features as training: "will this habit happen on its next day?"
                    habit = {'device_id': dev_id, 'action': act, 'minute': hour * 60 + minute,
                             'repeat_days': repeat_days, 'window': habit_window(cluster)}
                    confidence = home_model.probability(next_day_habit_features(actions, days, active_dates, habit))
                    engine, model_version = "ML", home_model.metadata.get("version", "unknown")
                    basis = "theo mô hình học máy của nhà bạn"
                else:
                    confidence = wilson_lower_bound(k, n, settings.WILSON_Z)
                    engine, model_version = "RULE_BASED", HABIT_ENGINE_VERSION
                    basis = "ước lượng thận trọng từ số ngày lặp lại"
                confidence = round(float(confidence), 4)
                if confidence < conf_threshold:
                    continue

                # Settings learned from the device state only make sense when turning on.
                params = cluster['learned_parameters'] if act == 'TURN_ON' else {}
                detail = ""
                if 'temperature' in params:
                    detail = f" ở {params['temperature']}°C"
                elif 'brightness' in params:
                    detail = f" với độ sáng {params['brightness']}%"
                day_word = "ngày thường" if repeat_days == [1, 2, 3, 4, 5] else (
                    "ngày cuối tuần" if repeat_days == [6, 7] else "ngày")
                verb = "bật" if act == "TURN_ON" else "tắt"
                reason = (
                    f"Bạn {verb} {dev_name}{detail} khoảng {hour:02d}:{minute:02d} vào {k}/{n} {day_word} gần đây "
                    f"(lệch ±{int(cluster['circ_std_minutes'])} phút). Độ tin cậy {confidence * 100:.0f}% {basis}."
                )
                proposals.append(RecommendationProposal(
                    deviceId=str(dev_id),
                    deviceName=dev_name,
                    roomId=str(sample.get('room_id')) if sample.get('room_id') else None,
                    roomName=sample.get('room_name'),
                    action=act,
                    parameters=params,
                    triggerType="SCHEDULE",
                    time=f"{hour:02d}:{minute:02d}:00",
                    repeatDays=repeat_days,
                    confidence=confidence,
                    eligible=True,
                    reason=reason,
                    engineType=engine,
                    modelVersion=model_version,
                ))

        # Same device, action and hour: keep the first (the backend also merges near-identical times).
        unique: List[RecommendationProposal] = []
        seen = set()
        for p in proposals:
            key = (p.deviceId, p.action, p.time[:2])
            if key not in seen:
                seen.add(key)
                unique.append(p)
        return unique, evaluated, len(unique)


model_manager = BehaviorModelManager()
