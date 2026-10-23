"""
HESTA Voice Service — HTTP Server (FastAPI)
Expose voice pipeline via REST API cho frontend web.

Endpoint:
  POST /api/voice/process  →  nhận text command + homeId, trả kết quả pipeline

Chạy: uvicorn server:app --host 0.0.0.0 --port 8001
Hoặc: python server.py
"""

import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path
import urllib.parse

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from pydantic import BaseModel


# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("voice-server")

# Load environment
load_dotenv()

# --- Global pipeline instance ---
_pipeline = None


def _get_pipeline():
    """Lazy-init pipeline."""
    global _pipeline
    if _pipeline is None:
        from app.pipeline import VoicePipeline
        config_path = Path("config/voice_config.yaml")
        logger.info("Khởi tạo Voice Pipeline từ %s ...", config_path)
        _pipeline = VoicePipeline.from_config(config_path=config_path)
        _pipeline.start()
        logger.info("✅ Voice Pipeline sẵn sàng (HTTP mode)")
    return _pipeline


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup/shutdown lifecycle."""
    _get_pipeline()
    yield
    global _pipeline
    if _pipeline:
        _pipeline.stop()
        _pipeline = None
        logger.info("Voice Pipeline stopped.")


app = FastAPI(
    title="HESTA Voice Service",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS — cho phép frontend gọi trực tiếp
ALLOWED_ORIGINS = os.environ.get("CORS_ORIGINS", "http://localhost:5173,http://localhost:3000").split(",")
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- API Key bảo vệ endpoint (nếu cần bảo vệ client ngoài, để trống = mở cho frontend web) ---
VOICE_API_KEY = os.environ.get("VOICE_API_KEY", "")


def _verify_api_key(x_api_key: str | None):
    """Kiểm tra API key từ header nếu VOICE_API_KEY được chỉ định."""
    if not VOICE_API_KEY:
        return  # Không cấu hình key cho client → cho phép gọi (Web / local frontend)
    if x_api_key != VOICE_API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")


# --- Request / Response models ---
class VoiceCommandRequest(BaseModel):
    text: str
    homeId: str = ""


class VoiceCommandResponse(BaseModel):
    success: bool
    message: str
    transcript: str = ""
    action: str | None = None
    room: str | None = None
    deviceType: str | None = None
    totalDurationMs: float = 0.0
    stepDurations: dict[str, float] = {}
    audioUrl: str | None = None


class HealthResponse(BaseModel):
    status: str
    pipelineReady: bool


# --- Helper TTS Synthesis ---
async def generate_tts_mp3(text: str, voice: str = "vi-VN-HoaiMyNeural") -> bytes:
    """Tạo âm thanh MP3 tiếng Việt chuẩn tự nhiên qua edge-tts."""
    import edge_tts

    clean_text = (
        text.replace("✅", "")
        .replace("⚠️", "")
        .replace("❌", "")
        .replace("⚡", "")
        .replace("📝", "")
        .strip()
    )
    if not clean_text:
        return b""

    comm = edge_tts.Communicate(clean_text, voice)
    mp3_bytes = bytearray()
    async for chunk in comm.stream():
        if chunk["type"] == "audio":
            mp3_bytes.extend(chunk["data"])
    return bytes(mp3_bytes)


# --- Endpoints ---
@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint."""
    return HealthResponse(
        status="ok",
        pipelineReady=_pipeline is not None,
    )


@app.get("/api/voice/tts")
async def get_voice_tts(
    text: str,
    voice: str = "vi-VN-HoaiMyNeural",
):
    """
    Phát âm thanh tiếng Việt chất lượng cao (Neural TTS) cho phản hồi.
    Mặc định: vi-VN-HoaiMyNeural (giọng nữ Hà Nội chuẩn tự nhiên).
    """
    clean_text = text.strip()
    if not clean_text:
        raise HTTPException(status_code=400, detail="Text is empty")

    try:
        mp3_data = await generate_tts_mp3(clean_text, voice)
        if not mp3_data:
            raise HTTPException(status_code=500, detail="Failed to synthesize speech")
        return Response(content=mp3_data, media_type="audio/mpeg")
    except Exception as e:
        logger.error("TTS generation error: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/voice/process", response_model=VoiceCommandResponse)
async def process_voice_command(
    request: VoiceCommandRequest,
    x_api_key: str | None = Header(None, alias="X-API-Key"),
):
    """
    Xử lý lệnh voice dạng text.

    Frontend gửi text (đã chuyển từ speech qua Web Speech API)
    + homeId → pipeline xử lý → trả kết quả.
    """
    _verify_api_key(x_api_key)

    pipeline = _get_pipeline()

    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text command is empty")

    # Cập nhật home_id nếu frontend gửi
    if request.homeId:
        pipeline.home_id = request.homeId

    logger.info("Processing command: '%s' (homeId=%s)", text, pipeline.home_id)

    try:
        result = pipeline.process_text(text)
    except Exception as e:
        logger.exception("Pipeline error")
        raise HTTPException(status_code=500, detail=str(e))

    audio_url = f"/api/voice/tts?text={urllib.parse.quote(result.message)}" if result.message else None

    return VoiceCommandResponse(
        success=result.success,
        message=result.message,
        transcript=result.transcript,
        action=result.intent.action if result.intent else None,
        room=result.intent.room if result.intent else None,
        deviceType=result.intent.device_type if result.intent else None,
        totalDurationMs=result.total_duration_ms,
        stepDurations=result.step_durations,
        audioUrl=audio_url,
    )


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("VOICE_SERVER_PORT", 8001))
    logger.info("Starting Voice HTTP Server on port %d ...", port)
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")
