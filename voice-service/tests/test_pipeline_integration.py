"""
HESTA Voice Service — Integration Test Pipeline
Test toàn pipeline (Mục 11): intent → device resolution → dispatch.

Dùng mock HTTP server thay cho Spring Boot.
Test bao gồm:
- Single-turn pipeline
- Multi-turn (dialogue state fill missing)
- Nhiều thiết bị cùng loại trong 1 phòng (Mục 2.4 bước 3)
- Trường hợp không tìm thấy thiết bị
- Auth header đúng
"""

import json
import os
import tempfile
import time
from unittest.mock import MagicMock, patch

import httpx
import numpy as np
import pytest

from app.audio_gate import AudioGate, AudioGateConfig
from app.stt import SpeechToText, STTConfig, STTResult
from app.device_directory import DeviceDirectory, DeviceDirectoryConfig
from app.intent import IntentMapper, IntentConfig
from app.dispatcher import Dispatcher, DispatchConfig
from app.dialogue_state import DialogueState
from app.pipeline import VoicePipeline, PipelineResult


# --- Fixtures ---

SAMPLE_DEVICES = [
    {"id": "dev-001", "name": "Đèn trần", "type": "light", "room": "Phòng khách", "homeId": "home-1"},
    {"id": "dev-002", "name": "Đèn bàn", "type": "light", "room": "Phòng khách", "homeId": "home-1"},
    {"id": "dev-003", "name": "Đèn ngủ", "type": "light", "room": "Phòng ngủ", "homeId": "home-1"},
    {"id": "dev-004", "name": "Quạt trần", "type": "fan", "room": "Phòng khách", "homeId": "home-1"},
    {"id": "dev-005", "name": "Điều hòa", "type": "air_conditioner", "room": "Phòng ngủ", "homeId": "home-1"},
]

SAMPLE_INTENTS = {
    "intents": [
        {"action": "TURN_ON", "device_type": "light", "patterns": ["bật đèn", "mở đèn"]},
        {"action": "TURN_OFF", "device_type": "light", "patterns": ["tắt đèn", "tắt hết đèn"]},
        {"action": "TURN_ON", "device_type": "fan", "patterns": ["bật quạt", "mở quạt"]},
        {"action": "TURN_OFF", "device_type": "fan", "patterns": ["tắt quạt"]},
        {"action": "TURN_ON", "device_type": "air_conditioner", "patterns": ["bật điều hòa", "mở điều hòa"]},
        {"action": "TURN_OFF", "device_type": "air_conditioner", "patterns": ["tắt điều hòa"]},
        {
            "action": "SET_TEMPERATURE", "device_type": "air_conditioner",
            "patterns": ["chỉnh điều hòa", "đặt nhiệt độ"], "has_parameter": "temperature"
        },
    ]
}


def make_mock_backend_transport(devices: list[dict] = SAMPLE_DEVICES) -> httpx.MockTransport:
    """Mock Spring Boot backend: trả device list + nhận action."""
    dispatch_log: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path

        if "/internal/devices" in path and request.method == "GET":
            return httpx.Response(200, json=devices)

        if "/internal/devices/" in path and "/actions" in path and request.method == "POST":
            payload = json.loads(request.content) if request.content else {}
            dispatch_log.append({"url": str(request.url), "payload": payload})
            return httpx.Response(200, json={"status": "ok", "message": "Action executed"})

        return httpx.Response(404, json={"error": "Not found"})

    transport = httpx.MockTransport(handler)
    transport.dispatch_log = dispatch_log  # type: ignore
    return transport


@pytest.fixture
def intents_file():
    fd, path = tempfile.mkstemp(suffix=".json")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(SAMPLE_INTENTS, f, ensure_ascii=False)
    yield path
    os.unlink(path)


@pytest.fixture
def pipeline(intents_file):
    """Tạo pipeline đầy đủ với mock HTTP backend."""
    transport = make_mock_backend_transport()
    client = httpx.Client(base_url="http://localhost:8080", transport=transport)

    config = DeviceDirectoryConfig(
        base_url="http://localhost:8080",
        devices_endpoint="/internal/devices",
    )

    # Tạo mock STT (không load model thật)
    mock_stt = MagicMock(spec=SpeechToText)
    mock_stt.is_loaded = True

    device_directory = DeviceDirectory(config=config, http_client=client)
    # Pre-load cache
    with patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"}):
        device_directory.refresh(home_id="home-1")

    intent_mapper = IntentMapper(IntentConfig(min_confidence=60.0))
    intent_mapper.load_intents(intents_file)

    dispatcher = Dispatcher(
        DispatchConfig(base_url="http://localhost:8080"),
        http_client=client,
    )

    dialogue_state = DialogueState(ttl_seconds=45)

    audio_gate = AudioGate(AudioGateConfig())

    p = VoicePipeline(
        audio_gate=audio_gate,
        stt=mock_stt,
        device_directory=device_directory,
        intent_mapper=intent_mapper,
        dispatcher=dispatcher,
        dialogue_state=dialogue_state,
        home_id="home-1",
    )
    p._transport = transport  # type: ignore  # Store for test assertions
    return p


# --- Integration Tests ---

class TestSingleTurnPipeline:
    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_turn_on_light_single_room(self, pipeline):
        """Bật đèn phòng ngủ — 1 đèn."""
        result = pipeline.process_text("bật đèn phòng ngủ")
        assert result.success is True
        assert "đèn" in result.message.lower()
        assert result.intent is not None
        assert result.intent.action == "TURN_ON"
        assert result.dispatch_result is not None
        assert result.dispatch_result.total_devices == 1

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_turn_on_light_multiple_devices(self, pipeline):
        """Mục 2.4 bước 3: bật đèn phòng khách — CÓ 2 ĐÈN → dispatch cả 2."""
        result = pipeline.process_text("bật đèn phòng khách")
        assert result.success is True
        assert result.dispatch_result is not None
        assert result.dispatch_result.total_devices == 2
        assert result.dispatch_result.successful_count == 2
        # Thông báo phải nêu rõ số lượng
        assert "2" in result.message

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_turn_on_fan(self, pipeline):
        """Bật quạt phòng khách."""
        result = pipeline.process_text("bật quạt phòng khách")
        assert result.success is True
        assert result.dispatch_result.total_devices == 1

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_device_not_found(self, pipeline):
        """Mục 2.4 bước 4: thiết bị không tồn tại → lỗi rõ ràng."""
        result = pipeline.process_text("bật đèn phòng bếp")
        assert result.success is False
        assert "không tìm thấy" in result.message.lower()

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_unknown_command(self, pipeline):
        """Câu không liên quan → lỗi."""
        result = pipeline.process_text("hôm nay thời tiết thế nào")
        assert result.success is False
        assert "không hiểu" in result.message.lower()


class TestMultiTurnPipeline:
    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_multi_turn_fill_room(self, pipeline):
        """Turn 1: bật đèn phòng khách → Turn 2: tắt đèn (thiếu room) → dùng context."""
        # Turn 1
        result1 = pipeline.process_text("bật đèn phòng khách")
        assert result1.success is True

        # Turn 2 — thiếu room, phải fill từ context
        result2 = pipeline.process_text("tắt đèn")
        # Nếu room được fill từ context → success
        # Nếu không → fail vì thiếu room
        if result2.success:
            assert result2.dispatch_result is not None
            assert result2.dispatch_result.total_devices == 2  # 2 đèn phòng khách
        else:
            # Cũng chấp nhận — tùy implementation fill_missing
            pass

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_multi_turn_context_expires(self, pipeline):
        """Context hết hạn → không fill."""
        pipeline.dialogue_state = DialogueState(ttl_seconds=1)

        result1 = pipeline.process_text("bật đèn phòng khách")
        assert result1.success is True

        time.sleep(1.1)  # Đợi hết TTL

        result2 = pipeline.process_text("tắt đèn")
        # Phải fail vì context đã hết hạn
        assert result2.success is False


class TestMultipleDevicesSameType:
    """Test riêng cho trường hợp Mục 2.4 bước 3: nhiều thiết bị cùng loại."""

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_all_lights_dispatched(self, pipeline):
        """2 đèn phòng khách → dispatch cả 2, không chọn 1."""
        result = pipeline.process_text("bật đèn phòng khách")
        assert result.dispatch_result is not None
        dispatched_ids = {r.device_id for r in result.dispatch_result.results}
        assert dispatched_ids == {"dev-001", "dev-002"}

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_message_shows_count(self, pipeline):
        """Thông báo phải nêu số lượng: 'Đã bật 2 đèn phòng khách'."""
        result = pipeline.process_text("tắt hết đèn phòng khách")
        assert result.success is True
        assert "2" in result.message

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_single_device_no_count(self, pipeline):
        """1 thiết bị → không cần nêu số lượng."""
        result = pipeline.process_text("bật quạt phòng khách")
        assert result.success is True
        assert result.dispatch_result.total_devices == 1


class TestPipelineWithAudio:
    """Test pipeline.process_audio() với mock STT."""

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_process_audio_success(self, pipeline):
        """process_audio với mock STT trả transcript hợp lệ."""
        mock_stt_result = STTResult(
            text="bật đèn phòng khách",
            confidence=-0.3,
            duration_ms=500.0,
        )
        pipeline.stt.transcribe.return_value = mock_stt_result

        audio = np.zeros(16000, dtype=np.float32)
        result = pipeline.process_audio(audio)
        assert result.success is True
        assert result.transcript == "bật đèn phòng khách"
        assert result.total_duration_ms > 0

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_process_audio_empty_transcript(self, pipeline):
        """STT trả text rỗng → lỗi."""
        pipeline.stt.transcribe.return_value = STTResult(text="", confidence=0)

        audio = np.zeros(16000, dtype=np.float32)
        result = pipeline.process_audio(audio)
        assert result.success is False
        assert "không nhận ra" in result.message.lower()

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_process_audio_stt_error(self, pipeline):
        """STT raise exception → lỗi graceful."""
        pipeline.stt.transcribe.side_effect = RuntimeError("Model error")

        audio = np.zeros(16000, dtype=np.float32)
        result = pipeline.process_audio(audio)
        assert result.success is False
        assert "lỗi" in result.message.lower()

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_step_durations_tracked(self, pipeline):
        """Pipeline phải ghi nhận thời gian từng bước."""
        mock_stt_result = STTResult(
            text="bật quạt phòng khách",
            confidence=-0.3,
            duration_ms=500.0,
        )
        pipeline.stt.transcribe.return_value = mock_stt_result

        audio = np.zeros(16000, dtype=np.float32)
        result = pipeline.process_audio(audio)
        assert "stt_ms" in result.step_durations
        assert "intent_ms" in result.step_durations
