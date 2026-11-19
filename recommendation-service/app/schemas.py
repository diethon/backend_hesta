from typing import Optional, Dict, Any, List
from pydantic import BaseModel, Field

class HealthResponse(BaseModel):
    status: str = "UP"
    version: str = "1.0.0"
    timestamp: str

class ModelStatusResponse(BaseModel):
    status: str  # READY, COLD_START (per home)
    version: str
    modelType: str = "RandomForestClassifier"
    trainedAt: Optional[str] = None
    trainSamples: Optional[int] = 0
    testSamples: Optional[int] = 0
    accuracy: Optional[float] = 0.0
    precision: Optional[float] = 0.0
    recall: Optional[float] = 0.0
    f1Score: Optional[float] = 0.0
    baselineAccuracy: Optional[float] = 0.0
    baselineF1: Optional[float] = 0.0
    confidenceThreshold: float = 0.80
    calibrated: bool = False
    labelDistribution: Dict[str, Any] = Field(default_factory=dict)
    metrics: Dict[str, Any] = Field(default_factory=dict)

class BatchRecommendationRequest(BaseModel):
    home_id: str
    user_id: Optional[str] = None
    from_time: Optional[str] = None
    to_time: Optional[str] = None
    threshold: Optional[float] = None

class RecommendationProposal(BaseModel):
    deviceId: Optional[str] = None
    deviceName: Optional[str] = None
    roomId: Optional[str] = None
    roomName: Optional[str] = None
    action: str
    parameters: Dict[str, Any] = Field(default_factory=dict)
    triggerType: str = "SCHEDULE"
    time: str
    repeatDays: List[int] = Field(default_factory=list)  # ISO 1=Mon..7=Sun; empty = every day
    confidence: float
    eligible: bool
    reason: str
    engineType: str = "ML"
    modelVersion: str

class BatchRecommendationResponse(BaseModel):
    recommendations: List[RecommendationProposal]
    total_evaluated: int
    eligible_count: int
    model_status: str

class TrainRequest(BaseModel):
    home_id: Optional[str] = None
    force: bool = False
    test_size: float = Field(default=0.20, gt=0.0, lt=0.5)
