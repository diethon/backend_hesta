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
import re
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
from app.scene_directory import SceneDirectory, SceneDirectoryConfig
from app.intent import IntentMapper, IntentConfig, IntentResult
from app.dispatcher import Dispatcher, DispatchConfig, DispatchBatchResult, SceneDispatchResult
from app.dialogue_state import DialogueState
from app.splitter import split_compound_command, combine_command_messages
from app.text_normalize import polish_transcript

logger = logging.getLogger(__name__)

# Từ khóa nhận diện lệnh điều khiển toàn bộ thiết bị / cả nhà (word-boundary để tránh match nhầm chuỗi con)
_ALL_KEYWORDS_RE = re.compile(
    r"\b(?:tất cả|tat ca|toàn bộ|toan bo|cả nhà|ca nha|hết|het|các|cac|mọi|moi|trong nhà|trong nha)\b",
    re.IGNORECASE,
)

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
    "scene": "kịch bản",
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
    error_code: str | None = None
    status: str = "SUCCESS"
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
        scene_directory: SceneDirectory | None = None,
    ):
        self.audio_gate = audio_gate
        self.stt = stt
        self.device_directory = device_directory
        self.scene_directory = scene_directory
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

        scene_directory = SceneDirectory(SceneDirectoryConfig(
            base_url=base_url,
            scenes_endpoint=backend_cfg.get("scenes_endpoint", "/internal/scenes"),
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
            scene_directory=scene_directory,
        )

    def start(self) -> None:
        """Khởi động pipeline: load model STT, bắt đầu refresh device & scene cache."""
        logger.info("Starting voice pipeline...")
        self.stt.load_model()
        self.device_directory.start(home_id=self.home_id)
        if self.scene_directory is not None:
            self.scene_directory.start(home_id=self.home_id)
        logger.info("Voice pipeline started.")

    def stop(self) -> None:
        """Dừng pipeline."""
        self.device_directory.stop()
        if self.scene_directory is not None:
            self.scene_directory.stop()
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

    def _execute_scene_command(
        self,
        text: str,
        intent: IntentResult,
        home_id: str,
        user_id: str,
        t_start: float,
        step_durations: dict[str, float],
        is_compound_sub: bool = False,
    ) -> PipelineResult:
        """Xử lý lệnh kích hoạt hoặc tắt Kịch bản (Scene) với cơ chế chống xung đột."""
        t0 = time.perf_counter()
        scene_query = getattr(intent, "scene_query", "") or (intent.parameters or {}).get("scene_query", "")
        if not scene_query and hasattr(self.dialogue_state, "get_hint_for"):
            scene_query = self.dialogue_state.get_hint_for(None, "scene")

        if self.scene_directory is None:
            return PipelineResult(
                success=False,
                message="Tính năng điều khiển kịch bản chưa được kích hoạt.",
                transcript=text,
                intent=intent,
                error_code="SCENE_UNAVAILABLE",
                status="FAILED",
                total_duration_ms=round((time.perf_counter() - t_start) * 1000, 1),
                step_durations=step_durations,
            )

        scene_aliases = (intent.parameters or {}).get("scene_aliases")
        is_natural_trigger = bool((intent.parameters or {}).get("is_natural_trigger"))
        scene = self.scene_directory.resolve_scene(scene_query, home_id=home_id, aliases=scene_aliases)
        step_durations["device_resolve_ms"] = (time.perf_counter() - t0) * 1000

        if scene is None:
            if is_natural_trigger and scene_query:
                msg = f"Nhà chưa có kịch bản {scene_query}. Bạn có thể tạo kịch bản này trong mục Tự động hóa."
            elif scene_query:
                msg = f"Không tìm thấy kịch bản {scene_query}."
            else:
                msg = "Không tìm thấy kịch bản nào phù hợp."
            return PipelineResult(
                success=False,
                message=msg,
                transcript=text,
                intent=intent,
                error_code="SCENE_NOT_FOUND",
                status="FAILED",
                total_duration_ms=round((time.perf_counter() - t_start) * 1000, 1),
                step_durations=step_durations,
            )

        mode = "DEACTIVATE" if intent.action in ("DEACTIVATE_SCENE", "TURN_OFF", "STOP", "CLOSE") else "ACTIVATE"
        intent.action = "DEACTIVATE_SCENE" if mode == "DEACTIVATE" else "ACTIVATE_SCENE"
        intent.device_type = "scene"
        intent.scene_query = scene.name

        t0 = time.perf_counter()
        scene_result = self.dispatcher.dispatch_scene(
            scene_id=scene.scene_id,
            scene_name=scene.name,
            mode=mode,
            home_id=home_id or scene.home_id,
            user_id=user_id,
        )
        step_durations["dispatch_ms"] = (time.perf_counter() - t0) * 1000

        message = scene_result.summary_message()

        if scene_result.success and not is_compound_sub:
            self.dialogue_state.update(
                room=None,
                device_type="scene",
                device_ids=[scene.scene_id],
                action=intent.action,
                home_id=home_id or scene.home_id,
                device_name_hint=scene.name,
            )

        return PipelineResult(
            success=scene_result.success,
            message=message,
            transcript=text,
            intent=intent,
            total_duration_ms=round((time.perf_counter() - t_start) * 1000, 1),
            step_durations=step_durations,
            error_code=scene_result.error_code,
            status=scene_result.status,
        )

    def _execute_single_command(
        self,
        text: str,
        custom_rooms: list[str],
        user_id: str = "",
        is_compound_sub: bool = False,
        custom_scenes: list[str] | None = None,
    ) -> PipelineResult:
        """Thực thi một câu lệnh đơn lẻ (Mapping -> Dialogue State -> Resolve -> Dispatch)."""
        t_start = time.perf_counter()
        step_durations: dict[str, float] = {}

        # 1. Intent Mapping
        t0 = time.perf_counter()
        intent = self.intent_mapper.map(text, custom_rooms=custom_rooms, custom_scenes=custom_scenes)
        step_durations["intent_ms"] = (time.perf_counter() - t0) * 1000

        if intent is None:
            return PipelineResult(
                success=False,
                message="Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé.",
                transcript=text,
                error_code="INTENT_NOT_FOUND",
                status="FAILED",
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        # Nhánh truy vấn hệ thống (liệt kê kịch bản / danh sách phòng / hủy lệnh)
        if intent.device_type == "system":
            if intent.action == "LIST_SCENES":
                scenes = self.scene_directory.list_scenes(self.home_id) if self.scene_directory else []
                enabled_scenes = [s.name for s in scenes if s.enabled]
                disabled_count = len(scenes) - len(enabled_scenes)
                if enabled_scenes:
                    names_str = ", ".join(enabled_scenes[:6])
                    extra = f" và {disabled_count} kịch bản đang tắt" if disabled_count > 0 else ""
                    msg = f"Nhà hiện có {len(enabled_scenes)} kịch bản đang bật: {names_str}{extra}. Bạn muốn bật kịch bản nào?"
                elif scenes:
                    msg = f"Nhà có {len(scenes)} kịch bản nhưng hiện đều đang tắt. Bạn hãy bật chúng trong mục Tự động hóa nhé."
                else:
                    msg = "Nhà hiện chưa có kịch bản nào. Bạn có thể tạo mới trong mục Tự động hóa."
                return PipelineResult(
                    success=True,
                    message=msg,
                    transcript=text,
                    intent=intent,
                    status="SUCCESS",
                    total_duration_ms=round((time.perf_counter() - t_start) * 1000, 1),
                    step_durations=step_durations,
                )
            if intent.action == "LIST_DEVICES":
                target_room = intent.room or ""
                devs = self.device_directory.resolve(target_room, "all", home_id=self.home_id, is_all=True)
                if devs:
                    names_str = ", ".join(d.device_name for d in devs[:6])
                    more = f" và {len(devs) - 6} thiết bị khác" if len(devs) > 6 else ""
                    scope_str = f"ở {target_room}" if target_room else "trong nhà"
                    msg = f"Hiện có {len(devs)} thiết bị {scope_str}: {names_str}{more}."
                else:
                    msg = f"Không tìm thấy thiết bị nào ở {target_room}." if target_room else "Chưa tìm thấy thiết bị nào trong nhà."
                return PipelineResult(
                    success=True,
                    message=msg,
                    transcript=text,
                    intent=intent,
                    status="SUCCESS",
                    total_duration_ms=round((time.perf_counter() - t_start) * 1000, 1),
                    step_durations=step_durations,
                )
            if intent.action == "LIST_ROOMS":
                rooms = self.device_directory.get_known_rooms(self.home_id)
                msg = f"Nhà hiện có {len(rooms)} phòng: {', '.join(rooms)}." if rooms else "Chưa tìm thấy danh sách phòng nào trong nhà."
                return PipelineResult(
                    success=True,
                    message=msg,
                    transcript=text,
                    intent=intent,
                    status="SUCCESS",
                    total_duration_ms=round((time.perf_counter() - t_start) * 1000, 1),
                    step_durations=step_durations,
                )
            if intent.action == "CANCEL_CONTEXT":
                self.dialogue_state.clear()
                return PipelineResult(
                    success=True,
                    message="Đã hủy bỏ yêu cầu.",
                    transcript=text,
                    intent=intent,
                    status="SUCCESS",
                    total_duration_ms=round((time.perf_counter() - t_start) * 1000, 1),
                    step_durations=step_durations,
                )

        # Nhánh chuyên biệt cho Kịch bản (Scene) khi người dùng gọi đích danh
        if intent.device_type == "scene":
            return self._execute_scene_command(
                text=text,
                intent=intent,
                home_id=self.home_id,
                user_id=user_id,
                t_start=t_start,
                step_durations=step_durations,
                is_compound_sub=is_compound_sub,
            )

        # 2. Nhận diện lệnh điều khiển toàn bộ ("tất cả", "toàn bộ", "hết", "cả nhà"...)
        # PHẢI chạy trước fill_missing() để "tắt toàn bộ đèn" không bị kế thừa nhầm
        # phòng từ câu lệnh trước trong dialogue_state (TTL 45s).
        is_all = (
            intent.device_type == "all"
            or "hết" in (intent.matched_pattern or "").lower()
            or bool(_ALL_KEYWORDS_RE.search(text))
        )

        # 3. Fill missing từ dialogue state (multi-turn)
        if is_all and intent.room is None:
            # Lệnh toàn bộ không nêu phòng (VD: "tắt toàn bộ đèn", "bật tất cả đèn") → áp dụng toàn nhà
            room, device_type, home_id = None, intent.device_type, self.home_id
        elif is_compound_sub and intent.room is not None and intent.device_type is not None:
            # Lệnh con trong chuỗi lệnh kép đã có đủ phòng & loại thiết bị → không phụ thuộc dialogue_state
            room, device_type, home_id = intent.room, intent.device_type, self.home_id
        else:
            room, device_type, home_id = self.dialogue_state.fill_missing(
                intent.room,
                intent.device_type,
                self.home_id,
                action=intent.action,
                device_name_hint=getattr(intent, "device_name_hint", ""),
            )

        # Nếu ngữ cảnh 45s vừa khôi phục device_type == "scene" (VD: "bật kịch bản nghỉ trưa" -> "tắt đi")
        if device_type == "scene":
            return self._execute_scene_command(
                text=text,
                intent=intent,
                home_id=home_id,
                user_id=user_id,
                t_start=t_start,
                step_durations=step_durations,
                is_compound_sub=is_compound_sub,
            )

        if not device_type:
            return PipelineResult(
                success=False,
                message="Bạn muốn điều khiển thiết bị hay kịch bản nào? Vui lòng nói rõ tên nhé.",
                transcript=text,
                intent=intent,
                error_code="DEVICE_TYPE_UNKNOWN",
                status="FAILED",
                total_duration_ms=(time.perf_counter() - t_start) * 1000,
                step_durations=step_durations,
            )

        # 4. Device Resolution
        t0 = time.perf_counter()
        hint = getattr(intent, "device_name_hint", "")
        if not hint and hasattr(self.dialogue_state, "get_hint_for"):
            hint = self.dialogue_state.get_hint_for(room, device_type)
        mode_query = (intent.parameters or {}).get("mode")

        # Nếu vừa thao tác đèn LED RGB trong ngữ cảnh 45s và người dùng nói rút gọn "giảm độ sáng xuống 40%" (không nhắc chữ "đèn"),
        # ưu tiên giữ nguyên loại led_rgb của ngữ cảnh gần nhất thay vì nhảy sang đèn thường!
        if (
            intent.action in {"SET_BRIGHTNESS", "INCREASE_BRIGHTNESS", "DECREASE_BRIGHTNESS"}
            and device_type == "light"
            and "đèn" not in text.lower()
            and "den" not in text.lower()
        ):
            ctx_now = self.dialogue_state.get_context()
            if ctx_now.is_valid and ctx_now.last_device_type == "led_rgb":
                device_type = "led_rgb"

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
        elif device_type == "light" and ("chiếu tv" in hint or "chieu tv" in hint):
            dev_type_vi = "đèn chiếu TV"
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
                }
                if mode_info.get("is_color"):
                    intent.parameters["color"] = mode_info["name"]
                else:
                    intent.parameters["mode"] = mode_info["name"]
            elif device_type == "light":
                intent.action = "SET_BRIGHTNESS"
                intent.parameters = {
                    "brightness": mode_info.get("brightness", 80),
                    "mode": mode_info["name"],
                }

        if len(devices) > 1 and not is_all and "hết" not in (intent.matched_pattern or ""):
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
            device_ids, intent.action, intent.parameters or None, user_id=user_id
        )
        step_durations["dispatch_ms"] = (time.perf_counter() - t0) * 1000

        message = dispatch_result.summary_message(
            intent.action, device_type, room, intent.parameters,
            pattern=intent.matched_pattern or "",
            hint=hint,
        )

        effective_room = room
        if effective_room is None and devices and len({d.room_name for d in devices if d.room_name}) == 1:
            effective_room = devices[0].room_name

        if dispatch_result.successful_count > 0 and not is_compound_sub:
            self.dialogue_state.update(
                room=effective_room, device_type=device_type,
                device_ids=device_ids, action=intent.action, home_id=home_id,
                device_name_hint=hint,
            )

        err_code = None
        status = "SUCCESS"
        is_partial = (not dispatch_result.all_success) and (dispatch_result.successful_count > 0)
        failed_results = [r for r in dispatch_result.results if not r.success]
        only_permission_skips = (
            is_partial
            and bool(failed_results)
            and all(
                r.error_code in ("ACCESS_DENIED", "UNAUTHORIZED") or r.status_code in (401, 403, 404)
                for r in failed_results
            )
        )
        if not dispatch_result.all_success and not only_permission_skips:
            err_codes = {r.error_code for r in dispatch_result.results if r.error_code}
            if "ACCESS_DENIED" in err_codes or any(r.status_code == 403 for r in dispatch_result.results):
                err_code = "ACCESS_DENIED"
                status = "ACCESS_DENIED"
            elif "UNAUTHORIZED" in err_codes or any(r.status_code == 401 for r in dispatch_result.results):
                err_code = "UNAUTHORIZED"
                status = "ACCESS_DENIED"
            elif "DEVICE_OFFLINE" in err_codes:
                err_code = "DEVICE_OFFLINE"
                status = "DEVICE_OFFLINE"
            elif "GATEWAY_DISCONNECTED" in err_codes:
                err_code = "GATEWAY_DISCONNECTED"
                status = "GATEWAY_DISCONNECTED"
            elif "CONNECTION_ERROR" in err_codes or any("timed out" in (r.error_message or "").lower() for r in dispatch_result.results):
                err_code = "TIMEOUT"
                status = "TIMEOUT"
            else:
                err_code = "FAILED"
                status = "FAILED"
            if is_partial:
                status = "PARTIAL_SUCCESS"

        total_ms = (time.perf_counter() - t_start) * 1000

        return PipelineResult(
            success=dispatch_result.all_success or is_partial,
            message=message,
            transcript=text,
            intent=intent,
            dispatch_result=dispatch_result,
            total_duration_ms=round(total_ms, 1),
            step_durations=step_durations,
            error_code=err_code,
            status=status,
        )

    def process_text(self, text: str, user_id: str = "") -> PipelineResult:
        """Xử lý lệnh từ text (hỗ trợ cả lệnh đơn và chuỗi lệnh kép).

        Args:
            text: câu lệnh tiếng Việt
            user_id: ID người dùng ra lệnh (để kiểm tra phân quyền RBAC)

        Returns:
            PipelineResult
        """
        t_start = time.perf_counter()
        polished_text = polish_transcript(text)
        known_rooms = self.device_directory.get_known_rooms(self.home_id)
        known_scenes = self.scene_directory.get_scene_names(self.home_id) if self.scene_directory else []
        sub_commands = split_compound_command(polished_text, custom_rooms=known_rooms)

        if len(sub_commands) <= 1:
            res = self._execute_single_command(
                polished_text,
                custom_rooms=known_rooms,
                user_id=user_id,
                custom_scenes=known_scenes,
            )
            res.transcript = polished_text
            res.total_duration_ms = round((time.perf_counter() - t_start) * 1000, 1)
            return res

        # Xử lý chuỗi câu lệnh kép (compound commands)
        # QUY TẮC ƯU TIÊN CHỐNG XUNG ĐỘT KỊCH BẢN (Specific Overrides General):
        # Nếu lệnh kép kết hợp Kịch bản + Thiết bị cụ thể (VD: "Bật kịch bản đi ngủ và bật đèn phòng khách"
        # hoặc "Bật đèn phòng khách và bật kịch bản đi ngủ") → chạy TUẦN TỰ: Kịch bản trước, Thiết bị cụ thể sau
        # để thiết bị người dùng nêu đích danh luôn giữ đúng trạng thái mong muốn, tránh race condition!
        import concurrent.futures

        def _is_scene_clause(cmd: str) -> bool:
            c_low = cmd.lower()
            if any(k in c_low for k in ("kịch bản", "kich ban", "kết bạn", "ket ban", "kích bản", "kịch bảng", "ngữ cảnh", "ngu canh")):
                return True
            preview = self.intent_mapper.map(cmd, custom_rooms=known_rooms, custom_scenes=known_scenes)
            return preview is not None and preview.device_type == "scene"

        scene_flags = [_is_scene_clause(cmd) for cmd in sub_commands]
        has_scene_and_device = any(scene_flags) and not all(scene_flags)

        sub_results: list[PipelineResult] = [None] * len(sub_commands)  # type: ignore
        combined_durations: dict[str, float] = {
            "intent_ms": 0.0,
            "device_resolve_ms": 0.0,
            "dispatch_ms": 0.0,
        }

        if has_scene_and_device:
            # Chạy tuần tự: tất cả lệnh kịch bản trước, rồi mới tới lệnh thiết bị cụ thể (Specific wins!)
            exec_order = [i for i, is_sc in enumerate(scene_flags) if is_sc] + [
                i for i, is_sc in enumerate(scene_flags) if not is_sc
            ]
            for idx in exec_order:
                r = self._execute_single_command(
                    sub_commands[idx],
                    custom_rooms=known_rooms,
                    user_id=user_id,
                    is_compound_sub=True,
                    custom_scenes=known_scenes,
                )
                sub_results[idx] = r
                for k in ("intent_ms", "device_resolve_ms", "dispatch_ms"):
                    combined_durations[k] += r.step_durations.get(k, 0.0)
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=min(4, len(sub_commands))) as executor:
                future_to_idx = {
                    executor.submit(
                        self._execute_single_command,
                        sub_cmd,
                        custom_rooms=known_rooms,
                        user_id=user_id,
                        is_compound_sub=True,
                        custom_scenes=known_scenes,
                    ): idx
                    for idx, sub_cmd in enumerate(sub_commands)
                }
                for fut, idx in future_to_idx.items():
                    r = fut.result()
                    sub_results[idx] = r
                    for k in ("intent_ms", "device_resolve_ms", "dispatch_ms"):
                        combined_durations[k] = max(combined_durations[k], r.step_durations.get(k, 0.0))

        # Cập nhật dialogue_state tuần tự cho TẤT CẢ các lệnh con thành công trong lệnh kép
        for r in sub_results:
            if r.success and r.intent and r.intent.device_type == "scene":
                self.dialogue_state.update(
                    room=None,
                    device_type="scene",
                    device_ids=[],
                    action=r.intent.action,
                    home_id=self.home_id,
                    device_name_hint=getattr(r.intent, "scene_query", ""),
                )
                continue
            if r.success and r.intent and r.dispatch_result and r.dispatch_result.successful_count > 0:
                hint_sub = getattr(r.intent, "device_name_hint", "")
                if not hint_sub and hasattr(self.dialogue_state, "get_hint_for"):
                    hint_sub = self.dialogue_state.get_hint_for(r.intent.room, r.intent.device_type)
                sub_room = r.intent.room
                if sub_room is None and r.dispatch_result.results:
                    all_candidates = self.device_directory.resolve("", r.intent.device_type or "all", home_id=self.home_id)
                    matched_devs = [
                        d for d in all_candidates
                        if d.device_id in [res.device_id for res in r.dispatch_result.results if res.success]
                    ]
                    dev_rooms = {d.room_name for d in matched_devs if d.room_name}
                    if len(dev_rooms) == 1:
                        sub_room = next(iter(dev_rooms))
                self.dialogue_state.update(
                    room=sub_room,
                    device_type=r.intent.device_type,
                    device_ids=[d.device_id for d in r.dispatch_result.results if d.success],
                    action=r.intent.action,
                    home_id=self.home_id,
                    device_name_hint=hint_sub,
                )

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
