"""
Tests cho các nâng cấp Voice AI:
1. TTS Semantic RAM Audio Fallback (không bao giờ im lặng khi thiết bị offline/lỗi hoặc Edge-TTS timeout).
2. Guardrails chống đoán bừa Intent cho câu vô nghĩa, thiết bị lạ, nhiễu.
3. Điều khiển Kịch bản (Scenes) theo số thứ tự, theo tên, tắt kịch bản, ngữ cảnh 45s.
4. Xử lý xung đột kịch bản: trùng trạng thái, ghi đè kịch bản cũ, xung đột nội bộ, và lệnh kép (Specific Overrides General).
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from unittest.mock import MagicMock, patch

import httpx
import pytest

from app.audio_gate import AudioGate, AudioGateConfig
from app.device_directory import DeviceDirectory, DeviceDirectoryConfig
from app.dialogue_state import DialogueState
from app.dispatcher import DispatchConfig, Dispatcher, SceneDispatchResult
from app.intent import IntentConfig, IntentMapper
from app.pipeline import VoicePipeline
from app.scene_directory import SceneDirectory, SceneDirectoryConfig
from app.stt import SpeechToText
from app.tts_manager import TTSManager


REAL_INTENTS_PATH = os.path.join(os.path.dirname(__file__), "..", "config", "intents_vi.json")


class TestTTSSemanticFallback:
    def test_semantic_ram_fallback_hits_cached_error_audio(self, tmp_path: Path):
        mgr = TTSManager(cache_dir=tmp_path / "cache", bundled_dir=tmp_path / "bundled")
        offline_mp3 = b"\xff\xf3\x64\xc4offline-hoaimy"
        unknown_mp3 = b"\xff\xf3\x64\xc4unknown-hoaimy"
        denied_mp3 = b"\xff\xf3\x64\xc4denied-hoaimy"

        mgr.save_to_cache("Thiết bị hiện đang ngoại tuyến hoặc chưa kết nối.", mgr.default_voice, offline_mp3)
        mgr.save_to_cache("Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé.", mgr.default_voice, unknown_mp3)
        mgr.save_to_cache("Bạn không có quyền điều khiển thiết bị này.", mgr.default_voice, denied_mp3)

        assert mgr.get_fallback_cached_audio(error_code="DEVICE_OFFLINE", message="Đèn chiếu TV hiện đang ngoại tuyến.") == offline_mp3
        assert mgr.get_fallback_cached_audio(error_code="INTENT_NOT_FOUND", message="Không hiểu") == unknown_mp3
        assert mgr.get_fallback_cached_audio(error_code="ACCESS_DENIED", message="Không có quyền") == denied_mp3


class TestStrictIntentGuardrails:
    @pytest.fixture
    def real_mapper(self):
        m = IntentMapper(IntentConfig(min_confidence=68.0))
        m.load_intents(REAL_INTENTS_PATH)
        return m

    @pytest.mark.parametrize("nonsense", [
        "alo alo 1 2 3",
        "hôm nay ăn gì",
        "xin chào bạn khỏe không",
        "trời hôm nay đẹp quá",
        "bật tủ lạnh",
        "tắt máy giặt",
        "mở nhạc lên đi",
        "đóng cuốn sách",
        "bật cái máy bay lên",
        "tắt cái gì đó không biết",
        "cảm ơn nhé",
        "ok được rồi",
        "mấy giờ rồi",
        "bật máy xay",
        "tắt điện thoại đi",
    ])
    def test_nonsense_and_unsupported_commands_return_none(self, real_mapper, nonsense):
        res = real_mapper.map(nonsense)
        assert res is None, f"Should reject '{nonsense}', but mapped to {res}"

    @pytest.mark.parametrize("phrase,expected_action,expected_query", [
        ("bật kịch bản 1", "ACTIVATE_SCENE", "1"),
        ("chạy kịch bản số 2", "ACTIVATE_SCENE", "2"),
        ("bật kịch bản một", "ACTIVATE_SCENE", "1"),
        ("bật kịch bản chào buổi sáng", "ACTIVATE_SCENE", "chào buổi sáng"),
        ("kích hoạt kịch bản xem phim", "ACTIVATE_SCENE", "xem phim"),
        ("tắt kịch bản nghỉ trưa", "DEACTIVATE_SCENE", "nghỉ trưa"),
        ("dừng kịch bản 1", "DEACTIVATE_SCENE", "1"),
        ("hủy kịch bản đi ngủ", "DEACTIVATE_SCENE", "đi ngủ"),
    ])
    def test_scene_intents_parsed_accurately(self, real_mapper, phrase, expected_action, expected_query):
        res = real_mapper.map(phrase)
        assert res is not None, f"Failed to map scene command: {phrase}"
        assert res.device_type == "scene"
        assert res.action == expected_action
        assert res.scene_query == expected_query


class TestScenePipelineAndConflicts:
    @staticmethod
    def _build_pipeline_with_scenes(scene_exec_override=None):
        devices = [
            {"id": "l-pk-1", "name": "Đèn phòng khách", "deviceType": "LIGHT", "roomName": "Phòng khách", "homeId": "h-1"},
            {"id": "l-ms-1", "name": "Đèn ngủ master", "deviceType": "LIGHT", "roomName": "Phòng ngủ master", "homeId": "h-1"},
        ]
        scenes = [
            {"id": "sc-1", "homeId": "h-1", "name": "Chào buổi sáng", "enabled": True, "index": 1, "actions": []},
            {"id": "sc-2", "homeId": "h-1", "name": "Nghỉ trưa", "enabled": True, "index": 2, "actions": []},
            {"id": "sc-3", "homeId": "h-1", "name": "Đi ngủ", "enabled": True, "index": 3, "actions": []},
        ]
        call_order: list[str] = []

        def handler(request: httpx.Request) -> httpx.Response:
            path = request.url.path
            if path == "/internal/devices" and request.method == "GET":
                return httpx.Response(200, json=devices)
            if path == "/internal/scenes" and request.method == "GET":
                return httpx.Response(200, json=scenes)
            if path.startswith("/internal/scenes/") and request.method == "POST":
                call_order.append(path)
                if scene_exec_override:
                    return scene_exec_override(path)
                sid = path.split("/")[3]
                mode = "DEACTIVATE" if path.endswith("/deactivate") else "ACTIVATE"
                sname = next((s["name"] for s in scenes if s["id"] == sid), "Unknown")
                return httpx.Response(200, json={
                    "code": 1000,
                    "result": {
                        "sceneId": sid,
                        "sceneName": sname,
                        "mode": mode,
                        "status": "SUCCESS",
                        "success": True,
                        "totalDevices": 2,
                        "executedCount": 2,
                        "alreadyInStateCount": 0,
                        "conflictResolvedCount": 0,
                        "overriddenCount": 0,
                        "offlineCount": 0,
                        "failedCount": 0,
                    },
                })
            if path.startswith("/internal/devices/") and path.endswith("/actions") and request.method == "POST":
                call_order.append(path)
                return httpx.Response(200, json={"code": 1000, "result": {"success": True, "status": "SUCCESS"}})
            return httpx.Response(404, json={"message": "Not found"})

        client = httpx.Client(base_url="http://localhost:8080", transport=httpx.MockTransport(handler))
        dd = DeviceDirectory(DeviceDirectoryConfig(base_url="http://localhost:8080"), http_client=client)
        dd.refresh(home_id="h-1")
        sd = SceneDirectory(SceneDirectoryConfig(base_url="http://localhost:8080"), http_client=client)
        sd.refresh(home_id="h-1")

        mapper = IntentMapper(IntentConfig(min_confidence=68.0))
        mapper.load_intents(REAL_INTENTS_PATH)

        pipeline = VoicePipeline(
            audio_gate=AudioGate(AudioGateConfig()),
            stt=MagicMock(spec=SpeechToText),
            device_directory=dd,
            scene_directory=sd,
            intent_mapper=mapper,
            dispatcher=Dispatcher(DispatchConfig(base_url="http://localhost:8080"), http_client=client),
            dialogue_state=DialogueState(ttl_seconds=45),
            home_id="h-1",
        )
        return pipeline, call_order

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_activate_by_index_and_name_and_45s_context_deactivate(self):
        p, calls = self._build_pipeline_with_scenes()

        # 1. Bật theo số thứ tự: kịch bản 1 -> Chào buổi sáng (sc-1)
        r1 = p.process_text("bật kịch bản 1")
        assert r1.success is True
        assert "Chào buổi sáng" in r1.message
        assert calls[-1] == "/internal/scenes/sc-1/execute"

        # 2. Bật theo tên: Nghỉ trưa (sc-2)
        r2 = p.process_text("bật kịch bản nghỉ trưa")
        assert r2.success is True
        assert "Nghỉ trưa" in r2.message
        assert calls[-1] == "/internal/scenes/sc-2/execute"

        # 3. Ngữ cảnh 45s: 'tắt đi' phải tắt kịch bản Nghỉ trưa (sc-2)
        r3 = p.process_text("tắt đi")
        assert r3.success is True
        assert "Nghỉ trưa" in r3.message
        assert calls[-1] == "/internal/scenes/sc-2/deactivate"

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_compound_scene_and_specific_device_runs_scene_first_then_device(self):
        """Lệnh kép 'bật đèn phòng khách và bật kịch bản đi ngủ' phải chạy kịch bản TRƯỚC, thiết bị cụ thể SAU."""
        p, calls = self._build_pipeline_with_scenes()

        res = p.process_text("bật đèn phòng khách và bật kịch bản đi ngủ")
        assert res.success is True
        assert len(res.sub_results) == 2
        # Quy tắc Specific Overrides General: kịch bản Đi ngủ (sc-3) chạy trước, đèn phòng khách (l-pk-1) chạy sau!
        assert calls == [
            "/internal/scenes/sc-3/execute",
            "/internal/devices/l-pk-1/actions",
        ]

    @patch.dict("os.environ", {"INTERNAL_API_KEY": "test-key"})
    def test_nonsense_command_returns_polite_cached_message(self):
        p, calls = self._build_pipeline_with_scenes()

        res = p.process_text("hôm nay trời mưa quá ăn gì bây giờ")
        assert res.success is False
        assert res.error_code == "INTENT_NOT_FOUND"
        assert res.message == "Tôi không hiểu yêu cầu này. Bạn vui lòng nói lại nhé."
        assert calls == []

    def test_scene_summary_messages_for_conflict_and_overlap_scenarios(self):
        # 1. Trùng trạng thái 100%
        r_same = SceneDispatchResult(
            scene_id="1", scene_name="Chào buổi sáng", mode="ACTIVATE",
            success=True, status="ALREADY_IN_STATE", total_devices=3, already_in_state_count=3,
        )
        assert "đúng trạng thái" in r_same.summary_message().lower()

        # 2. Ghi đè kịch bản cũ + 1 thiết bị đã đúng trạng thái + 1 xung đột nội bộ
        r_override = SceneDispatchResult(
            scene_id="3", scene_name="Đi ngủ", mode="ACTIVATE",
            success=True, status="SUCCESS", total_devices=3,
            executed_count=2, already_in_state_count=1,
            conflict_resolved_count=1, overridden_count=2, overridden_scene_name="Chào buổi sáng",
        )
        msg = r_override.summary_message()
        assert "Chào buổi sáng" in msg and "Đi ngủ" in msg
        assert "điều chỉnh 2 thiết bị" in msg
        assert "ưu tiên trạng thái sau cùng" in msg

        # 3. Một phần thiết bị ngoại tuyến
        r_partial = SceneDispatchResult(
            scene_id="1", scene_name="Chào buổi sáng", mode="ACTIVATE",
            success=True, status="PARTIAL", total_devices=4,
            executed_count=3, offline_count=1,
        )
        assert "3 thiết bị" in r_partial.summary_message()
        assert "1 thiết bị đang ngoại tuyến" in r_partial.summary_message()
