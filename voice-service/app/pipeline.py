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
from app.splitter import split_compound_command, combine_command_messages

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
    sub_results: list[PipelineResult] = field(default_factory=list)


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
            request_timeout_seconds=float(backend_cfg.get("request_timeout_seconds", 3.0)),
            max_workers=int(backend_cfg.get("max_workers", 10)),
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
        """Xử lý 1 đoạn audio đã ghi xong (toàn bộ pipeline từ STT -> intent -> dispatch).

        Hỗ trợ cả câu lệnh đơn lẻ lẫn câu lệnh kép (tách liên từ).

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
            logger.error("Lỗi STT: %s", e)
            return PipelineResult(
                success=False,
                message="Lỗi nhận dạng giọng nói.",
                error=str(e),
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
            )
        stt_ms = (time.perf_counter() - t0) * 1000

        if not stt_result.text.strip():
            return PipelineResult(
                success=False,
                message="Không nhận ra giọng nói. Vui lòng thử lại.",
                transcript="",
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations={"stt_ms": stt_ms},
            )

        transcript = stt_result.text.strip()
        logger.info("STT: '%s' (%.0fms)", transcript, stt_ms)

        # Chuyển tiếp tới process_text để xử lý logic lệnh đơn/kép
        res = self.process_text(transcript)
        res.step_durations["stt_ms"] = stt_ms
        res.total_duration_ms = round((time.perf_counter() - t_start) * 1000, 1)

        logger.info(
            "Pipeline done: %s → %s (total=%.0fms, stt=%.0fms)",
            transcript, res.message, res.total_duration_ms, stt_ms,
        )
        return res

    def _execute_single_command(self, text: str, custom_rooms: list[str]) -> PipelineResult:
        """Thực thi một câu lệnh đơn lẻ (Mapping -> Dialogue State -> Resolve -> Dispatch)."""
        t_start = time.perf_counter()
        step_durations: dict[str, float] = {}

        # 1. Intent Mapping
        t0 = time.perf_counter()
        intent = self.intent_mapper.map(text, custom_rooms=custom_rooms)
        step_durations["intent_ms"] = (time.perf_counter() - t0) * 1000

        if intent is None:
            return PipelineResult(
                success=False,
                message=f"Không hiểu lệnh: \"{text}\".",
                transcript=text,
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        # 2. Fill missing từ dialogue state (multi-turn)
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

        # 3. Device Resolution
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

        if mode_query and device_type == "light":
            rgb_test = self.device_directory.resolve(room or "", "led_rgb", home_id=home_id)
            if rgb_test:
                device_type = "led_rgb"

        devices = self.device_directory.resolve(
            room or "", device_type, home_id=home_id,
            device_name_hint=hint, is_all=is_all
        )
        if not devices and room and intent.room is None:
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

        # 4. Dispatch
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

    def process_text(self, text: str) -> PipelineResult:
        """Xử lý lệnh từ text (hỗ trợ cả lệnh đơn và chuỗi lệnh kép).

        Args:
            text: câu lệnh tiếng Việt

        Returns:
            PipelineResult
        """
        t_start = time.perf_counter()
        known_rooms = self.device_directory.get_known_rooms(self.home_id)
        sub_commands = split_compound_command(text, custom_rooms=known_rooms)

        if len(sub_commands) <= 1:
            res = self._execute_single_command(text, custom_rooms=known_rooms)
            res.transcript = text
            res.total_duration_ms = round((time.perf_counter() - t_start) * 1000, 1)
            return res

        # Xử lý chuỗi câu lệnh kép (compound commands)
        sub_results: list[PipelineResult] = []
        combined_durations: dict[str, float] = {
            "intent_ms": 0.0,
            "device_resolve_ms": 0.0,
            "dispatch_ms": 0.0,
        }

        for sub_cmd in sub_commands:
            r = self._execute_single_command(sub_cmd, custom_rooms=known_rooms)
            sub_results.append(r)
            for k in ("intent_ms", "device_resolve_ms", "dispatch_ms"):
                combined_durations[k] += r.step_durations.get(k, 0.0)

        # Gộp thông báo xác nhận tự nhiên
        combined_message = combine_command_messages([(r.success, r.message) for r in sub_results])

        # Gộp các kết quả dispatch từ các lệnh con
        combined_dispatch = DispatchBatchResult(
            results=[],
            total_devices=0,
            successful_count=0,
            failed_count=0,
            total_duration_ms=0.0,
        )
        for r in sub_results:
            if r.dispatch_result:
                combined_dispatch.results.extend(r.dispatch_result.results)
                combined_dispatch.total_devices += r.dispatch_result.total_devices
                combined_dispatch.successful_count += r.dispatch_result.successful_count
                combined_dispatch.failed_count += r.dispatch_result.failed_count
                combined_dispatch.total_duration_ms += r.dispatch_result.total_duration_ms

        any_success = any(r.success for r in sub_results)
        representative_intent = next((r.intent for r in sub_results if r.success and r.intent), None)
        if not representative_intent and sub_results:
            representative_intent = sub_results[0].intent

        total_ms = (time.perf_counter() - t_start) * 1000
        combined_durations["total_ms"] = total_ms

        logger.info(
            "Compound pipeline done: %s → %s (total=%.0fms, %d sub-commands)",
            text, combined_message, total_ms, len(sub_commands),
        )

        return PipelineResult(
            success=any_success,
            message=combined_message,
            transcript=text,
            intent=representative_intent,
            dispatch_result=combined_dispatch,
            total_duration_ms=round(total_ms, 1),
            step_durations=combined_durations,
            sub_results=sub_results,
        )

    def start(self) -> None:
        """Khởi động pipeline (start device directory refresh background thread)."""
        self.device_directory.start(home_id=self.home_id)
        known_rooms = self.device_directory.get_known_rooms(self.home_id)
        if known_rooms:
            self.intent_mapper.set_custom_rooms(known_rooms)

    def stop(self) -> None:
        """Dừng pipeline và giải phóng tài nguyên background."""
        self.device_directory.stop()
