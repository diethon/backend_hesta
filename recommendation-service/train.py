"""
Trains the per-home habit models from the command line.
Run:
    python train.py                    # every home that has device actions
    python train.py --home-id <uuid>   # one home
    python train.py --home-id <uuid> --plot
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from app.main import train_one  # noqa: E402  (same code path as POST /train)
from app.pipeline import list_homes_with_actions  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Train HESTA per-home habit models")
    parser.add_argument("--home-id", type=str, default=None, help="Home to train (default: all homes)")
    parser.add_argument("--test-size", type=float, default=0.20, help="Share of the newest days kept for testing")
    parser.add_argument("--plot", action="store_true", help="Write evaluation plots to artifacts/homes/<id>/plots")
    args = parser.parse_args()

    homes = [args.home_id] if args.home_id else list_homes_with_actions()
    if not homes:
        print("Chưa có nhà nào có thao tác thiết bị trong behavior_events.")
        return 2

    for home_id in homes:
        status = train_one(home_id, args.test_size)
        print(f"\n=== Home {home_id}: {status.status} ===")
        if status.status != "READY":
            print(f"  {status.metrics.get('reason') or status.metrics.get('last_training')}")
            continue
        m = status.metrics
        print(f"  Version {status.version} | {m.get('train_days')} ngày train, {m.get('test_days')} ngày test, "
              f"{m.get('user_actions')} thao tác tay, {m.get('devices')} thiết bị")
        print(f"  F1 bật/tắt {status.f1Score:.3f} (luật đơn giản {status.baselineF1:.3f}) | "
              f"precision {status.precision:.3f} | recall {status.recall:.3f} | calibrated={status.calibrated}")
        print(f"  {m.get('promotion_reason')}")
        if args.plot:
            import generate_training_plots
            print(f"  Plots: {generate_training_plots.plot_home(home_id, args.test_size)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
