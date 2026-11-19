"""
HESTA Voice Service — HTTP Server (FastAPI)
Expose voice pipeline via REST API cho frontend web.

Endpoint:
  POST /api/voice/process  →  nhận text command + homeId, trả kết quả pipeline

Chạy: uvicorn server:app --host 0.0.0.0 --port 8001
Hoặc: python server.py
"""

import asyncio
import base64
import logging
import os
import urllib.parse
from contextlib import asynccontextmanager
from pathlib import Path

from typing import Any

import httpx
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel

from app.tts_manager import clean_tts_text, detect_audio_mime, tts_manager


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
    pipeline = _get_pipeline()

    # 1. Nạp danh sách phòng & thiết bị thực tế từ Backend đồng bộ ngay khi khởi động
    try:
        if pipeline and pipeline.device_directory:
            pipeline.device_directory.refresh(home_id=pipeline.home_id)
            logger.info("✅ Initial device directory refreshed on startup")
    except Exception as e:
        logger.warning("Could not do initial device directory refresh: %s", e)

    # 2. Nạp toàn bộ file giọng nói có sẵn từ disk cache và assets vào RAM (0ms latency, 0 network calls)
    try:
        tts_manager.preload_disk_cache()
    except Exception as e:
        logger.debug("Could not preload disk cache on startup: %s", e)

    # Pre-warm kết nối HTTP Keep-Alive tới Backend & Database pool (chống khựng lệnh đầu tiên)
    try:
        if pipeline and hasattr(pipeline, "dispatcher") and pipeline.dispatcher:
            client = pipeline.dispatcher._get_client()
            client.get(
                "/internal/devices",
                headers={"X-Internal-Api-Key": pipeline.dispatcher._get_api_key()},
                timeout=1.5,
            )
            logger.info("✅ Pre-warmed connection to Backend & Database pool")
    except Exception as e:
        logger.debug("Backend pre-ping warning: %s", e)

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

# CORS — cho phép frontend gọi trực tiếp (hỗ trợ localhost, LAN IP và Public VPS domain)
cors_kwargs: dict[str, Any] = {
    "allow_methods": ["*"],
    "allow_headers": ["*"],
    "allow_credentials": True,
    "allow_origin_regex": r"^https?://.*$",
}
try:
    # Hỗ trợ Chrome Private Network Access (PNA): gọi từ Public HTTPS domain (hesta.homes) -> Private LAN IP
    app.add_middleware(CORSMiddleware, allow_private_network=True, **cors_kwargs)
except TypeError:
    app.add_middleware(CORSMiddleware, **cors_kwargs)

# --- API Key bảo vệ endpoint (nếu cần bảo vệ client ngoài, để trống = mở cho frontend web) ---
VOICE_API_KEY = os.environ.get("VOICE_API_KEY", "")
AI_CAMERA_UPSTREAM = os.environ.get("AI_CAMERA_UPSTREAM", "http://127.0.0.1:5000").rstrip("/")


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
    userId: str = ""


class VoiceCommandResponse(BaseModel):
    success: bool
    message: str
    transcript: str = ""
    action: str | None = None
    room: str | None = None
    deviceType: str | None = None
    errorCode: str | None = None
    status: str | None = None
    totalDurationMs: float = 0.0
    stepDurations: dict[str, float] = {}
    audioUrl: str | None = None
    audioData: str | None = None
    subCommands: list[dict[str, Any]] = []


class WarmupRoomRequest(BaseModel):
    room: str
    devices: list[str] | None = None


class HealthResponse(BaseModel):
    status: str
    pipelineReady: bool
    tts: dict[str, Any] = {}


# --- Endpoints ---
@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint."""
    return HealthResponse(
        status="ok",
        pipelineReady=_pipeline is not None,
        tts=tts_manager.get_engine_status(),
    )


async def _proxy_camera_json(method: str, path: str) -> Response:
    """Proxy a short-lived JSON request to the local Orange Pi AI camera service."""
    url = f"{AI_CAMERA_UPSTREAM}{path}"
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(3.0)) as client:
            upstream = await client.request(method, url)
    except httpx.RequestError as exc:
        raise HTTPException(status_code=503, detail=f"AI camera upstream unavailable: {exc}") from exc

    return Response(
        content=upstream.content,
        status_code=upstream.status_code,
        media_type=upstream.headers.get("content-type", "application/json"),
        headers={"Cache-Control": "no-store"},
    )


@app.get("/camera/video_feed")
async def proxy_camera_video_feed():
    """Stream live MJPEG frames from the local AI camera service without buffering."""
    client = httpx.AsyncClient(timeout=httpx.Timeout(connect=5.0, read=None, write=10.0, pool=5.0))
    upstream = None
    try:
        req = client.build_request("GET", f"{AI_CAMERA_UPSTREAM}/video_feed")
        upstream = await client.send(req, stream=True)
    except httpx.RequestError as exc:
        await client.aclose()
        raise HTTPException(status_code=503, detail=f"AI camera stream unavailable: {exc}") from exc

    if upstream.status_code != 200:
        status_code = upstream.status_code
        await upstream.aclose()
        await client.aclose()
        raise HTTPException(status_code=status_code, detail="AI camera stream returned non-200 status")

    content_type = upstream.headers.get("content-type", "multipart/x-mixed-replace; boundary=frame")

    async def stream_chunks():
        try:
            async for chunk in upstream.aiter_bytes(chunk_size=16384):
                yield chunk
        except (asyncio.CancelledError, httpx.HTTPError):
            pass
        finally:
            await upstream.aclose()
            await client.aclose()

    return StreamingResponse(
        stream_chunks(),
        media_type=content_type,
        headers={
            "Cache-Control": "no-store, no-cache, must-revalidate, max-age=0",
            "Pragma": "no-cache",
            "X-Accel-Buffering": "no",
        },
    )


@app.get("/camera/api/status")
async def proxy_camera_status():
    return await _proxy_camera_json("GET", "/api/status")


@app.post("/camera/api/dismiss")
async def proxy_camera_dismiss():
    return await _proxy_camera_json("POST", "/api/dismiss")


@app.post("/camera/api/trigger_demo_fall")
async def proxy_camera_trigger_demo_fall():
    return await _proxy_camera_json("POST", "/api/trigger_demo_fall")


@app.post("/api/voice/warmup-room")
async def warmup_room_endpoint(request: WarmupRoomRequest):
    """Kích hoạt pre-warm âm thanh ngầm cho phòng mới được tạo từ Web / DB."""
    room_name = request.room.strip()
    if not room_name:
        raise HTTPException(status_code=400, detail="Room name is empty")

    tts_manager.warmup_room(room_name, request.devices)
    return {
        "status": "accepted",
        "message": f"Pre-warming scheduled for room: '{room_name}'",
    }


@app.get("/api/voice/tts")
async def get_voice_tts(
    text: str,
    voice: str = "vi-VN-HoaiMyNeural",
):
    """
    Phát âm thanh tiếng Việt chất lượng cao (Neural TTS) có caching siêu tốc (< 5ms).
    """
    clean_text = clean_tts_text(text)
    if not clean_text:
        raise HTTPException(status_code=400, detail="Text is empty")

    try:
        audio_bytes = await tts_manager.synthesize(
            clean_text,
            voice,
            timeout_sec=3.5,
            fast_mode=True,
        )
        if not audio_bytes:
            raise HTTPException(status_code=500, detail="Failed to synthesize speech")
        mime = detect_audio_mime(audio_bytes)
        return Response(
            content=audio_bytes,
            media_type=mime,
            headers={"Cache-Control": "public, max-age=86400"},
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error("TTS generation error: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/voice/process", response_model=VoiceCommandResponse)
async def process_voice_command(
    request: VoiceCommandRequest,
    x_api_key: str | None = Header(None, alias="X-API-Key"),
    x_user_id: str | None = Header(None, alias="X-User-Id"),
):
    """
    Xử lý lệnh voice dạng text.

    Frontend gửi text (đã chuyển từ speech qua Web Speech API)
    + homeId + userId → pipeline xử lý → trả kết quả kèm audio MP3 tức thì nếu có sẵn trong cache.
    """
    _verify_api_key(x_api_key)

    pipeline = _get_pipeline()

    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text command is empty")

    # Cập nhật home_id nếu frontend gửi
    if request.homeId:
        pipeline.home_id = request.homeId

    user_id = request.userId or (x_user_id or "")

    logger.info("Processing command: '%s' (homeId=%s, userId=%s)", text, pipeline.home_id, user_id or "anonymous")

    try:
        result = pipeline.process_text(text, user_id=user_id)
    except Exception as e:
        logger.exception("Pipeline error")
        raise HTTPException(status_code=500, detail=str(e))

    # Tối ưu hóa phản hồi giọng nói (Ưu tiên Base64 trả thẳng để phát tức thì < 5ms)
    audio_url = None
    audio_data = None
    if result.message:
        clean_msg = clean_tts_text(result.message)
        audio_url = f"/api/voice/tts?text={urllib.parse.quote(clean_msg)}"

        # 1. Kiểm tra nhanh trong cache (RAM/Disk/Bundled) -> < 2ms
        cached_bytes = tts_manager.get_cached_audio(clean_msg)
        if cached_bytes:
            mime = detect_audio_mime(cached_bytes)
            audio_data = f"data:{mime};base64,{base64.b64encode(cached_bytes).decode('ascii')}"
        else:
            # 2. Nếu là lệnh kép (>= 2 lệnh con), ghép trực tiếp các file MP3 giọng Hoài My từ RAM cache (< 1ms)
            #    Ngược lại gọi Edge-TTS Hoài My 1 lần gọn (<= 3.5s), chỉ fallback khi mất mạng/lỗi.
            try:
                sub_results = getattr(result, "sub_results", []) or []
                if len(sub_results) >= 2:
                    sub_msgs = [s.message for s in sub_results if getattr(s, "message", None)]
                    synthesized_bytes = await tts_manager.synthesize_compound(
                        sub_msgs,
                        clean_msg,
                        timeout_sec=3.5,
                    )
                else:
                    synthesized_bytes = await tts_manager.synthesize(
                        clean_msg,
                        timeout_sec=3.5,
                        fast_mode=True,
                    )
                if synthesized_bytes:
                    mime = detect_audio_mime(synthesized_bytes)
                    audio_data = f"data:{mime};base64,{base64.b64encode(synthesized_bytes).decode('ascii')}"
            except Exception as e:
                logger.debug("Inline TTS synthesize skipped/timeout: %s", e)

    sub_commands_data = []
    for sub in getattr(result, "sub_results", []):
        sub_commands_data.append({
            "message": sub.message,
            "success": sub.success,
            "action": sub.intent.action if sub.intent else None,
            "room": sub.intent.room if sub.intent else None,
            "deviceType": sub.intent.device_type if sub.intent else None,
            "errorCode": getattr(sub, "error_code", None),
            "status": getattr(sub, "status", None),
        })

    return VoiceCommandResponse(
        success=result.success,
        message=result.message,
        transcript=result.transcript,
        action=result.intent.action if result.intent else None,
        room=result.intent.room if result.intent else None,
        deviceType=result.intent.device_type if result.intent else None,
        errorCode=getattr(result, "error_code", None),
        status=getattr(result, "status", None),
        totalDurationMs=result.total_duration_ms,
        stepDurations=result.step_durations,
        audioUrl=audio_url,
        audioData=audio_data,
        subCommands=sub_commands_data,
    )


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("VOICE_SERVER_PORT", 8001))
    logger.info("Starting Voice HTTP Server on port %d ...", port)
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")
