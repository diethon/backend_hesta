import hmac
import logging
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException, status

from app.config import settings
from app.model import model_manager
from app.pipeline import ColdStartException, list_homes_with_actions, load_events_from_db
from app.schemas import (
    BatchRecommendationRequest,
    BatchRecommendationResponse,
    HealthResponse,
    ModelStatusResponse,
    TrainRequest,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("recommendation-service")

if not settings.API_KEY:
    logger.warning("ML_API_KEY is not set: every caller can use this service. Set it outside local development.")

app = FastAPI(
    title=settings.APP_NAME,
    version=settings.APP_VERSION,
    description="HESTA habit recommendations. Called only by the HESTA backend (server to server, no CORS).",
)


def require_api_key(x_api_key: Optional[str] = Header(default=None)) -> None:
    """Only the backend may call the service; it sends the shared secret in X-API-Key."""
    if settings.API_KEY and not hmac.compare_digest(x_api_key or "", settings.API_KEY):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid API key")


def parse_time(value: Optional[str]) -> Optional[datetime]:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Invalid time: {value}")


@app.get("/health", response_model=HealthResponse)
def health():
    return HealthResponse(status="UP", version=settings.APP_VERSION,
                          timestamp=datetime.now(timezone.utc).isoformat())


@app.get("/model/status", response_model=ModelStatusResponse, dependencies=[Depends(require_api_key)])
def get_model_status(home_id: Optional[str] = None):
    return model_manager.get_status(home_id)


@app.post("/recommendations/generate", response_model=BatchRecommendationResponse,
          dependencies=[Depends(require_api_key)])
def generate_recommendations(request: BatchRecommendationRequest):
    if not request.home_id:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="home_id is required")
    proposals, evaluated, eligible = model_manager.generate_recommendations_for_home(
        home_id=request.home_id,
        from_time=parse_time(request.from_time),
        to_time=parse_time(request.to_time),
        threshold=request.threshold,
    )
    return BatchRecommendationResponse(
        recommendations=proposals,
        total_evaluated=evaluated,
        eligible_count=eligible,
        model_status="READY" if model_manager.is_ready(request.home_id) else "COLD_START",
    )


def train_one(home_id: str, test_size: float) -> ModelStatusResponse:
    since = datetime.now(timezone.utc) - timedelta(days=settings.TRAIN_LOOKBACK_DAYS)
    try:
        metadata = model_manager.train_home(home_id, load_events_from_db(home_id=home_id, from_time=since),
                                            test_size=test_size)
    except ColdStartException as cse:
        logger.info("Home %s stays in cold start: %s", home_id, cse)
        response = model_manager.get_status(home_id)
        if response.status == "COLD_START":
            response.metrics = {"reason": str(cse)}
        else:
            response.metrics = {**response.metrics, "last_training": str(cse)}
        return response
    response = model_manager.get_status(home_id)
    response.metrics = {**response.metrics, "promoted": metadata["promoted"],
                        "promotion_reason": metadata["promotion_reason"]}
    return response


@app.post("/train", response_model=ModelStatusResponse, dependencies=[Depends(require_api_key)])
def train_model(request: TrainRequest = TrainRequest()):
    """Trains one home's model, or every home that has device actions when home_id is omitted."""
    try:
        if request.home_id:
            return train_one(request.home_id, request.test_size)
        results = {}
        for home in list_homes_with_actions():
            try:
                results[home] = train_one(home, request.test_size).status
            except Exception as ex:
                logger.error("Training home %s failed: %s", home, ex, exc_info=True)
                results[home] = "ERROR"
        ready = sum(1 for s in results.values() if s == "READY")
        return ModelStatusResponse(
            status="READY" if ready else "COLD_START",
            version="per-home",
            confidenceThreshold=settings.CONFIDENCE_THRESHOLD,
            metrics={"homes": results, "ready_homes": ready},
        )
    except Exception as ex:
        logger.error("Model training failed: %s", ex, exc_info=True)
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                            detail=f"Quá trình huấn luyện mô hình thất bại: {ex}")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host=settings.HOST, port=settings.PORT, reload=False)
