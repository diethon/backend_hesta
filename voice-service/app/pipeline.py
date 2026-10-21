"""
HESTA Voice Service — Pipeline Orchestrator (Mục 2.1)
Ghép nối toàn bộ pipeline: Audio Gate → STT → Device Resolution → Intent → Dispatch

Luồng single-turn:
  Audio buffer → STT → extract intent → resolve devices → dispatch

Luồng multi-turn:
  Câu thiếu thông tin → fill từ dialogue_state → resolve → dispatch
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import yaml
from dotenv import load_dotenv

from app.audio_gate import AudioGate, AudioGateConfig
from app.stt import SpeechToText, STTConfig, STTResult
from app.device_directory import DeviceDirectory, DeviceDirectoryConfig
from app.intent import IntentMapper, IntentConfig, IntentResult
from app.dispatcher import Dispatcher, DispatchConfig, DispatchBatchResult
from app.dialogue_state import DialogueState

logger = logging.getLogger(__name__)

# Bản dịch device_type → tiếng Việt để hiển thị lỗi cho người dùng
DEVICE_TYPE_VI = {
    "light": "đèn",
    "fan": "quạt",
    "air_conditioner": "điều hòa",
    "door_lock": "khóa cửa",
    "curtain": "rèm",
    "water_heater": "bình nóng lạnh",
    "gate": "cổng",
    "water_pump": "máy bơm nước",
    "led_rgb": "đèn RGB",
    "socket": "ổ cắm",
    "tv": "tivi",
    "all": "thiết bị",
}


@dataclass
class PipelineResult:
    """Kết quả xử lý 1 lệnh voice."""
    success: bool
    message: str                        # Thông báo cho người dùng (tiếng Việt)
    transcript: str = ""                # Transcript từ STT
    intent: IntentResult | None = None
    dispatch_result: DispatchBatchResult | None = None
    total_duration_ms: float = 0.0
    step_durations: dict[str, float] = field(default_factory=dict)
    error: str = ""


class VoicePipeline:
    """
    Orchestrator chính — ghép nối toàn bộ pipeline voice control.

    Sử dụng:
        pipeline = VoicePipeline.from_config("config/voice_config.yaml")
        pipeline.start()

        # Xử lý 1 lệnh (audio đã ghi xong)
        result = pipeline.process_audio(audio_array)

        pipeline.stop()
    """

    def __init__(
        self,
        audio_gate: AudioGate,
        stt: SpeechToText,
        device_directory: DeviceDirectory,
        intent_mapper: IntentMapper,
        dispatcher: Dispatcher,
        dialogue_state: DialogueState,
        home_id: str = "",
    ):
        self.audio_gate = audio_gate
        self.stt = stt
        self.device_directory = device_directory
        self.intent_mapper = intent_mapper
        self.dispatcher = dispatcher
        self.dialogue_state = dialogue_state
        self.home_id = home_id

    @classmethod
    def from_config(cls, config_path: str | Path,
                    intents_path: str | Path | None = None,
                    home_id: str = "") -> "VoicePipeline":
        """Tạo pipeline từ file config YAML.

        Args:
            config_path: path tới voice_config.yaml
            intents_path: path tới intents_vi.json (nếu None, lấy từ config)
            home_id: ID nhà mặc định
        """
        # Load .env
        load_dotenv()

        config_path = Path(config_path)
        with open(config_path, "r", encoding="utf-8") as f:
            raw_config = yaml.safe_load(f)

        # Parse configs
        stt_cfg = raw_config.get("stt", {})
        gate_cfg = raw_config.get("audio_gate", {})
        backend_cfg = raw_config.get("backend", {})
        device_dir_cfg = raw_config.get("device_directory", {})
        intent_cfg = raw_config.get("intent", {})

        import os
        base_url = os.environ.get("BACKEND_BASE_URL", backend_cfg.get("base_url", "http://localhost:8080"))

        # Tạo các module
        audio_gate = AudioGate(AudioGateConfig(
            rms_threshold=gate_cfg.get("rms_threshold", 500),
            min_speech_ms=gate_cfg.get("min_speech_ms", 250),
            max_silence_ms=gate_cfg.get("max_silence_ms", 800),
        ))

        stt = SpeechToText(STTConfig(
            model_size=stt_cfg.get("model_size", "base"),
            language=stt_cfg.get("language", "vi"),
            compute_type=stt_cfg.get("compute_type", "int8"),
            vad_filter=stt_cfg.get("vad_filter", True),
            initial_prompt=stt_cfg.get("initial_prompt", None),
            temperature=stt_cfg.get("temperature", None),
            condition_on_previous_text=stt_cfg.get("condition_on_previous_text", None),
            repetition_penalty=stt_cfg.get("repetition_penalty", 1.2),
            no_repeat_ngram_size=stt_cfg.get("no_repeat_ngram_size", 3),
            hotwords=stt_cfg.get("hotwords", None),
        ))

        device_directory = DeviceDirectory(DeviceDirectoryConfig(
            base_url=base_url,
            devices_endpoint=backend_cfg.get("devices_endpoint", "/internal/devices"),
            api_key_env=backend_cfg.get("internal_api_key_env", "INTERNAL_API_KEY"),
            refresh_interval_seconds=device_dir_cfg.get("refresh_interval_seconds", 60),
        ))

        intent_mapper = IntentMapper(IntentConfig(
            mapping_file=intent_cfg.get("mapping_file", "intents_vi.json"),
            min_confidence=intent_cfg.get("min_confidence", 50.0),
            context_ttl_seconds=intent_cfg.get("context_ttl_seconds", 45),
        ))

        # Load intents
        if intents_path is None:
            intents_path = config_path.parent / intent_cfg.get("mapping_file", "intents_vi.json")
        intent_mapper.load_intents(intents_path)

        dispatcher = Dispatcher(DispatchConfig(
            base_url=base_url,
            action_endpoint_template=backend_cfg.get(
                "action_endpoint", "/internal/devices/{device_id}/actions"
            ),
            api_key_env=backend_cfg.get("internal_api_key_env", "INTERNAL_API_KEY"),
        ))

        dialogue_state = DialogueState(
            ttl_seconds=intent_cfg.get("context_ttl_seconds", 45),
        )

        return cls(
            audio_gate=audio_gate,
            stt=stt,
            device_directory=device_directory,
            intent_mapper=intent_mapper,
            dispatcher=dispatcher,
            dialogue_state=dialogue_state,
            home_id=home_id,
        )

    def start(self) -> None:
        """Khởi động pipeline: load model STT, bắt đầu refresh device cache."""
        logger.info("Starting voice pipeline...")
        self.stt.load_model()
        self.device_directory.start(home_id=self.home_id)
        logger.info("Voice pipeline started.")

    def stop(self) -> None:
        """Dừng pipeline."""
        self.device_directory.stop()
        self.dispatcher.close()
        logger.info("Voice pipeline stopped.")

    def process_audio(self, audio: np.ndarray) -> PipelineResult:
        """Xử lý 1 đoạn audio đã ghi xong (output từ audio gate).

        Đây là entry point chính cho single-turn pipeline.

        Args:
            audio: numpy array (int16/float32, 16kHz mono)

        Returns:
            PipelineResult
        """
        t_start = time.perf_counter()
        step_durations: dict[str, float] = {}

        # === BƯỚC 1: STT ===
        t0 = time.perf_counter()
        try:
            stt_result = self.stt.transcribe(audio)
        except Exception as e:
            return PipelineResult(
                success=False,
                message="Lỗi nhận dạng giọng nói.",
                error=str(e),
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
            )
        step_durations["stt_ms"] = (time.perf_counter() - t0) * 1000

        if not stt_result.text.strip():
            return PipelineResult(
                success=False,
                message="Không nhận ra giọng nói. Vui lòng thử lại.",
                transcript="",
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        transcript = stt_result.text.strip()
        logger.info("STT: '%s' (%.0fms)", transcript, step_durations["stt_ms"])

        # === BƯỚC 2: Intent Mapping ===
        t0 = time.perf_counter()
        intent = self.intent_mapper.map(transcript)
        step_durations["intent_ms"] = (time.perf_counter() - t0) * 1000

        if intent is None:
            return PipelineResult(
                success=False,
                message=f"Không hiểu lệnh: \"{transcript}\". Vui lòng thử lại.",
                transcript=transcript,
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        # === BƯỚC 3: Fill missing từ dialogue state (multi-turn) ===
        room, device_type, home_id = self.dialogue_state.fill_missing(
            intent.room, intent.device_type, self.home_id
        )

        if not device_type:
            return PipelineResult(
                success=False,
                message="Không xác định được loại thiết bị. Vui lòng nói rõ hơn.",
                transcript=transcript,
                intent=intent,
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        # === BƯỚC 4: Device Resolution ===
        t0 = time.perf_counter()
        is_all = (
            device_type == "all"
            or "hết" in (intent.matched_pattern or "").lower()
            or any(kw in transcript.lower() for kw in [
                "tất cả", "tat ca", "toàn bộ", "toan bo", "cả nhà", "ca nha",
                "hết", "het", "các", "cac", "mọi", "moi"
            ])
        )
        hint = getattr(intent, "device_name_hint", "")
        mode_query = (intent.parameters or {}).get("mode")

        # Nếu có yêu cầu chế độ ánh sáng / màu sắc mà device_type đang là light, ưu tiên tìm xem phòng có đèn LED RGB không
        if mode_query and device_type == "light":
            rgb_test = self.device_directory.resolve(room or "", "led_rgb", home_id=home_id)
            if rgb_test:
                device_type = "led_rgb"

        devices = self.device_directory.resolve(
            room or "", device_type, home_id=home_id,
            device_name_hint=hint, is_all=is_all
        )
        if not devices and room and intent.room is None:
            # Phòng đến từ context nhưng không có loại thiết bị này -> fallback tìm toàn bộ nhà
            devices = self.device_directory.resolve(
                "", device_type, home_id=home_id,
                device_name_hint=hint, is_all=is_all
            )
            if devices:
                room = None
        step_durations["device_resolve_ms"] = (time.perf_counter() - t0) * 1000

        if device_type == "led_rgb":
            if "rgb" in (intent.matched_pattern or "").lower():
                dev_type_vi = "đèn RGB"
            elif "led" in (intent.matched_pattern or "").lower():
                dev_type_vi = "đèn LED"
            else:
                dev_type_vi = "đèn LED RGB"
        elif device_type == "light" and hint == "ngủ":
            dev_type_vi = "đèn ngủ"
        else:
            dev_type_vi = DEVICE_TYPE_VI.get(device_type, device_type)

        if not devices:
            msg = f"Không tìm thấy {dev_type_vi} nào ở {room}." if room else f"Không tìm thấy {dev_type_vi} nào trong nhà."
            return PipelineResult(
                success=False,
                message=msg,
                transcript=transcript,
                intent=intent,
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        # Xử lý tham số mode nếu có: tra cứu preset hoặc màu tùy chỉnh
        if mode_query:
            mode_info = self.device_directory.find_light_mode(mode_query, devices=devices, home_id=home_id)
            if mode_info is None:
                room_str = f" ở {room}" if room else ""
                return PipelineResult(
                    success=False,
                    message=f"Không tìm thấy chế độ hoặc màu \"{mode_query}\"{room_str}. Bạn có thể tạo màu này trên ứng dụng trước.",
                    transcript=transcript,
                    intent=intent,
                    total_duration_ms=(time.perf_counter() - t_start) * 1000,
                    step_durations=step_durations,
                )

            if device_type == "led_rgb":
                intent.action = "SET_COLOR"
                intent.parameters = {
                    "r": mode_info["r"],
                    "g": mode_info["g"],
                    "b": mode_info["b"],
                    "hex": mode_info.get("hex", ""),
                    "brightness": mode_info.get("brightness", 100),
                    "mode": mode_info["name"],
                }
            elif device_type == "light":
                intent.action = "SET_BRIGHTNESS"
                intent.parameters = {
                    "brightness": mode_info.get("brightness", 80),
                    "mode": mode_info["name"],
                }

        # Nếu có nhiều thiết bị thuộc loại này ở nhiều phòng khác nhau mà người dùng không chỉ rõ phòng (trừ lệnh "tắt hết/bật hết")
        if len(devices) > 1 and not room and not is_all and "hết" not in (intent.matched_pattern or ""):
            distinct_rooms = {d.room_name for d in devices if d.room_name}
            if len(distinct_rooms) > 1:
                return PipelineResult(
                    success=False,
                    message=f"Nhà có {len(devices)} {dev_type_vi} ở nhiều phòng khác nhau. Vui lòng nói rõ tên phòng.",
                    transcript=transcript,
                    intent=intent,
                    total_duration_ms=(time.perf_counter() - t_start) * 1000,
                    step_durations=step_durations,
                )

        # === BƯỚC 5: Dispatch ===
        t0 = time.perf_counter()
        device_ids = [d.device_id for d in devices]
        dispatch_result = self.dispatcher.dispatch_batch(
            device_ids, intent.action, intent.parameters or None
        )
        step_durations["dispatch_ms"] = (time.perf_counter() - t0) * 1000

        # Tạo thông báo
        message = dispatch_result.summary_message(
            intent.action, device_type, room, intent.parameters,
            pattern=intent.matched_pattern or "",
            hint=hint,
        )

        # Cập nhật dialogue state cho multi-turn
        if dispatch_result.all_success:
            self.dialogue_state.update(
                room=room,
                device_type=device_type,
                device_ids=device_ids,
                action=intent.action,
                home_id=home_id,
            )

        total_ms = (time.perf_counter() - t_start) * 1000
        step_durations["total_ms"] = total_ms

        logger.info(
            "Pipeline done: %s → %s (total=%.0fms, stt=%.0fms, intent=%.0fms, "
            "resolve=%.0fms, dispatch=%.0fms)",
            transcript, message,
            total_ms,
            step_durations.get("stt_ms", 0),
            step_durations.get("intent_ms", 0),
            step_durations.get("device_resolve_ms", 0),
            step_durations.get("dispatch_ms", 0),
        )

        return PipelineResult(
            success=dispatch_result.all_success,
            message=message,
            transcript=transcript,
            intent=intent,
            dispatch_result=dispatch_result,
            total_duration_ms=round(total_ms, 1),
            step_durations=step_durations,
        )

    def process_text(self, text: str) -> PipelineResult:
        """Xử lý lệnh từ text (bỏ qua STT, dùng để test nhanh).

        Args:
            text: câu lệnh tiếng Việt

        Returns:
            PipelineResult
        """
        t_start = time.perf_counter()
        step_durations: dict[str, float] = {}

        # Skip STT, đi thẳng vào intent mapping
        t0 = time.perf_counter()
        intent = self.intent_mapper.map(text)
        step_durations["intent_ms"] = (time.perf_counter() - t0) * 1000

        if intent is None:
            return PipelineResult(
                success=False,
                message=f"Không hiểu lệnh: \"{text}\".",
                transcript=text,
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        room, device_type, home_id = self.dialogue_state.fill_missing(
            intent.room, intent.device_type, self.home_id
        )

        if not device_type:
            return PipelineResult(
                success=False,
                message="Không xác định được loại thiết bị.",
                transcript=text,
                intent=intent,
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        t0 = time.perf_counter()
        is_all = (
            device_type == "all"
            or "hết" in (intent.matched_pattern or "").lower()
            or any(kw in text.lower() for kw in [
                "tất cả", "tat ca", "toàn bộ", "toan bo", "cả nhà", "ca nha",
                "hết", "het", "các", "cac", "mọi", "moi"
            ])
        )
        hint = getattr(intent, "device_name_hint", "")
        mode_query = (intent.parameters or {}).get("mode")

        # Nếu có yêu cầu chế độ ánh sáng / màu sắc mà device_type đang là light, ưu tiên tìm xem phòng có đèn LED RGB không
        if mode_query and device_type == "light":
            rgb_test = self.device_directory.resolve(room or "", "led_rgb", home_id=home_id)
            if rgb_test:
                device_type = "led_rgb"

        devices = self.device_directory.resolve(
            room or "", device_type, home_id=home_id,
            device_name_hint=hint, is_all=is_all
        )
        if not devices and room and intent.room is None:
            # Phòng đến từ context nhưng không có loại thiết bị này -> fallback tìm toàn bộ nhà
            devices = self.device_directory.resolve(
                "", device_type, home_id=home_id,
                device_name_hint=hint, is_all=is_all
            )
            if devices:
                room = None
        step_durations["device_resolve_ms"] = (time.perf_counter() - t0) * 1000

        if device_type == "led_rgb":
            if "rgb" in (intent.matched_pattern or "").lower():
                dev_type_vi = "đèn RGB"
            elif "led" in (intent.matched_pattern or "").lower():
                dev_type_vi = "đèn LED"
            else:
                dev_type_vi = "đèn LED RGB"
        elif device_type == "light" and hint == "ngủ":
            dev_type_vi = "đèn ngủ"
        else:
            dev_type_vi = DEVICE_TYPE_VI.get(device_type, device_type)

        if not devices:
            msg = f"Không tìm thấy {dev_type_vi} nào ở {room}." if room else f"Không tìm thấy {dev_type_vi} nào trong nhà."
            return PipelineResult(
                success=False,
                message=msg,
                transcript=text,
                intent=intent,
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        # Xử lý tham số mode nếu có: tra cứu preset hoặc màu tùy chỉnh
        if mode_query:
            mode_info = self.device_directory.find_light_mode(mode_query, devices=devices, home_id=home_id)
            if mode_info is None:
                room_str = f" ở {room}" if room else ""
                return PipelineResult(
                    success=False,
                    message=f"Không tìm thấy chế độ hoặc màu \"{mode_query}\"{room_str}. Bạn có thể tạo màu này trên ứng dụng trước.",
                    transcript=text,
                    intent=intent,
                    total_duration_ms=(time.perf_counter() - t_start) * 1000,
                    step_durations=step_durations,
                )

            if device_type == "led_rgb":
                intent.action = "SET_COLOR"
                intent.parameters = {
                    "r": mode_info["r"],
                    "g": mode_info["g"],
                    "b": mode_info["b"],
                    "hex": mode_info.get("hex", ""),
                    "brightness": mode_info.get("brightness", 100),
                    "mode": mode_info["name"],
                }
            elif device_type == "light":
                intent.action = "SET_BRIGHTNESS"
                intent.parameters = {
                    "brightness": mode_info.get("brightness", 80),
                    "mode": mode_info["name"],
                }

        # Nếu có nhiều thiết bị thuộc loại này ở nhiều phòng khác nhau mà người dùng không chỉ rõ phòng (trừ lệnh "tắt hết/bật hết")
        if len(devices) > 1 and not room and not is_all and "hết" not in (intent.matched_pattern or ""):
            distinct_rooms = {d.room_name for d in devices if d.room_name}
            if len(distinct_rooms) > 1:
                return PipelineResult(
                    success=False,
                    message=f"Nhà có {len(devices)} {dev_type_vi} ở nhiều phòng khác nhau. Vui lòng nói rõ tên phòng.",
                    transcript=text,
                    intent=intent,
                    total_duration_ms=(time.perf_counter() - t_start) * 1000,
                    step_durations=step_durations,
                )

        t0 = time.perf_counter()
        device_ids = [d.device_id for d in devices]
        dispatch_result = self.dispatcher.dispatch_batch(
            device_ids, intent.action, intent.parameters or None
        )
        step_durations["dispatch_ms"] = (time.perf_counter() - t0) * 1000

        message = dispatch_result.summary_message(
            intent.action, device_type, room, intent.parameters,
            pattern=intent.matched_pattern or "",
            hint=hint,
        )

        if dispatch_result.all_success:
            self.dialogue_state.update(
                room=room, device_type=device_type,
                device_ids=device_ids, action=intent.action, home_id=home_id,
            )

        total_ms = (time.perf_counter() - t_start) * 1000

        return PipelineResult(
            success=dispatch_result.all_success,
            message=message,
            transcript=text,
            intent=intent,
            dispatch_result=dispatch_result,
            total_duration_ms=round(total_ms, 1),
            step_durations=step_durations,
        )
