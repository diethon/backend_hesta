import os
from pathlib import Path
from dataclasses import dataclass

BASE_DIR = Path(__file__).resolve().parent.parent


def _load_dotenv(path: Path) -> None:
    """Reads KEY=VALUE lines from .env for local runs; real environment variables win."""
    if not path.exists():
        return
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


_load_dotenv(BASE_DIR / '.env')


@dataclass
class Settings:
    APP_NAME: str = "HESTA Smart Home ML Service"
    APP_VERSION: str = "2.0.0"
    HOST: str = os.getenv("HOST", "0.0.0.0")
    PORT: int = int(os.getenv("PORT", "8000"))

    # Secrets come only from the environment / .env (never commit them).
    DATABASE_URL: str = os.getenv("DATABASE_URL", "")
    # Shared secret with the backend (header X-API-Key). Empty = no check, for local development only.
    API_KEY: str = os.getenv("ML_API_KEY", "")

    BASE_DIR: Path = BASE_DIR
    ARTIFACTS_DIR: Path = BASE_DIR / "artifacts" / "homes"

    CONFIDENCE_THRESHOLD: float = float(os.getenv("CONFIDENCE_THRESHOLD", "0.80"))
    MIN_SAMPLES_REQUIRED: int = int(os.getenv("MIN_SAMPLES_REQUIRED", "20"))   # manual on/off actions
    MIN_TRAIN_DAYS: int = int(os.getenv("MIN_TRAIN_DAYS", "7"))
    TRAIN_LOOKBACK_DAYS: int = int(os.getenv("TRAIN_LOOKBACK_DAYS", "60"))
    RANDOM_STATE: int = 42

    TIMEZONE: str = os.getenv("TIMEZONE", "Asia/Ho_Chi_Minh")

    # Events from the backend's behaviour simulator (demo data). Off in production: they are not real habits.
    INCLUDE_SIMULATED: bool = os.getenv("ML_INCLUDE_SIMULATED", "false").lower() == "true"
    # Candidate model files kept per home for audit (model_latest is separate and always kept).
    KEEP_MODEL_VERSIONS: int = int(os.getenv("KEEP_MODEL_VERSIONS", "10"))

    # A habit must occur on at least this share of the user's active days (of the matching weekday/weekend type)
    MIN_HABIT_SUPPORT: float = float(os.getenv("MIN_HABIT_SUPPORT", "0.4"))
    # Fewer active days than this and a share like 3/3 says nothing yet.
    MIN_HABIT_DAYS: int = int(os.getenv("MIN_HABIT_DAYS", "5"))
    # z of the Wilson lower bound used as confidence before a model exists (1.0 ≈ one-sided 84%).
    WILSON_Z: float = float(os.getenv("WILSON_Z", "1.0"))


settings = Settings()
