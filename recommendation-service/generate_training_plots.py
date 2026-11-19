"""
Evaluation plots for one home's habit model (for reports). Needs matplotlib (not a service dependency):
    pip install matplotlib
    python generate_training_plots.py <home_id>
"""

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import joblib
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from sklearn.calibration import calibration_curve  # noqa: E402
from sklearn.metrics import ConfusionMatrixDisplay  # noqa: E402

sys.path.insert(0, str(Path(__file__).resolve().parent))

from app.config import settings  # noqa: E402
from app.pipeline import FEATURE_COLUMNS, build_habit_dataset, load_events_from_db  # noqa: E402


def plot_home(home_id: str, test_size: float = 0.20) -> Path:
    home_dir = settings.ARTIFACTS_DIR / home_id
    bundle = joblib.load(home_dir / "model_latest.joblib")
    model = bundle["model"]
    since = datetime.now(timezone.utc) - timedelta(days=settings.TRAIN_LOOKBACK_DAYS)
    dataset = build_habit_dataset(load_events_from_db(home_id=home_id, from_time=since),
                                  settings.MIN_SAMPLES_REQUIRED, settings.MIN_TRAIN_DAYS)
    days = sorted(dataset["day"].unique())
    test = dataset[dataset["day"].isin(days[-max(2, int(round(len(days) * test_size))):])]
    X, y = test[FEATURE_COLUMNS], test["label"]
    out = home_dir / "plots"
    out.mkdir(parents=True, exist_ok=True)

    # 1. Confusion matrix on the newest (test) days
    fig, ax = plt.subplots(figsize=(5, 4))
    ConfusionMatrixDisplay.from_predictions(y, model.predict(X), ax=ax, colorbar=False)
    ax.set_title("Ma trận nhầm lẫn (các ngày kiểm thử)")
    fig.tight_layout()
    fig.savefig(out / "01_confusion_matrix.png", dpi=150)
    plt.close(fig)

    # 2. Feature importance (averaged over calibration folds when calibrated)
    forests = [c.estimator for c in getattr(model, "calibrated_classifiers_", [])] or [model]
    importance = np.mean([f.feature_importances_ for f in forests], axis=0)
    order = np.argsort(importance)
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.barh([FEATURE_COLUMNS[i] for i in order], importance[order], color="#5bc0eb")
    ax.set_title("Mức quan trọng của đặc trưng")
    fig.tight_layout()
    fig.savefig(out / "02_feature_importance.png", dpi=150)
    plt.close(fig)

    # 3. Reliability: does "80%" really happen 80% of the time?
    fig, ax = plt.subplots(figsize=(5, 4))
    truth, predicted = calibration_curve(y, model.predict_proba(X)[:, list(model.classes_).index(1)],
                                         n_bins=5, strategy="quantile")
    ax.plot(predicted, truth, marker="o", label="Thói quen xảy ra")
    ax.plot([0, 1], [0, 1], linestyle="--", color="#9aa5b1", label="Hoàn hảo")
    ax.set_xlabel("Xác suất dự đoán")
    ax.set_ylabel("Tỉ lệ xảy ra thật")
    ax.set_title("Độ tin cậy có đúng nghĩa không")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out / "03_reliability.png", dpi=150)
    plt.close(fig)
    return out


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python generate_training_plots.py <home_id>")
        sys.exit(1)
    print(plot_home(sys.argv[1]))
